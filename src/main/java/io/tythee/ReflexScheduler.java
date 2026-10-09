package io.tythee;

import io.tythee.config.ModConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

public class ReflexScheduler {
    // ===== 估计量(新控制律仍需要): lead = sim + sub, 以及一帧投影用的 estGpu =====
    private final float alpha = 0.85f;
    private Long estimateCpuTime = null;
    private Long estimateSimTime = null;
    private Long estimateSubmissionTime = null;

    // GPU 单帧工作时长估计: 加权环形窗口(最新帧权重 0.333, 有效记忆约 3-4 帧)
    private final int gpuWindowSize = 60;
    private final float weightBase = 1.5f;
    private final float[] gpuWeights;
    private final long[] gpuTimeRingBuffer = new long[gpuWindowSize];
    private int ringBufferIndex = 0;
    private int validSamples = 0;

    // 命令缓冲采集器池 + 在途帧队列
    private final ObjectPool<GpuTimeCollector> collectorPool = new ObjectPool<>(
            GpuTimeCollector::new,
            GpuTimeCollector::reset
    );
    private final Deque<GpuTimeCollector> gpuTimeCollectorDeque = new ArrayDeque<>();
    private GpuTimeCollector currentCollector = null;
    private long currentFrameId = 0L;

    // 上一帧(已测)的 GPU 结束时刻 —— 新控制律的对齐锚点
    private Long lastFrameGpuEndTimeSystem = null;

    private static final double FRAC_OVERSLEEP_DEADBAND = 0.02;  // Oversleep 死区 = 2% estGpu(帧间空转计数用)
    private static final long PARK_QUANTUM_NS = 1_000_000L;      // 休眠轮询粒度; 自旋阈值亦由它推导
    // 停车过冲的运行时自适应: parkNanos 的实际时长取决于 OS 定时器分辨率
    // (Windows 默认 15.6ms, 若 JVM 未提升分辨率, "睡 1ms"可能实睡 15ms, Oversleep 直接爆炸)。
    // 用【实测过冲】自旋阈值自适应: 过冲大就多自旋, 精度不依赖系统配置。无拍脑盐藏数。
    private double parkOvershootNs = 0.0;
    private boolean parkSeen = false;

    // ---- 诊断用: 最近一次"等待计算"的内部量, 用于日志还原"为什么本次等 0 还是 4ms" ----
    private long dbgLeadTimeNs = 0L;
    private long dbgEstFinishRelNs = 0L;
    private long dbgSleepTimeNs = 0L;
    private long dbgDequeSize = 0L;
    private long dbgGpuEndAgeNs = -1L;
    private long dbgOversleepDeadbandNs = 0L;
    private long dbgAlignErrNs = Long.MIN_VALUE;   // 对齐误差 = renderSubmitEnd − 上一帧 gpuRenderEnd
    private long dbgGpuFreeNs = Long.MIN_VALUE;    // GPU 变闲时刻(投影)的绝对时间戳
    private boolean dbgRecordedThisFrame = false;
    private boolean dbgProjErrValid = false;
    private long dbgProjErrNs = 0L;
    private final Stat projErrStat = new Stat();
    private Long pendingProjectionNs = null;
    /** sleep() 算出的暂存标志: 本次等待是否真的拿到过正目标, 由下一帧 startFrame 取走。 */
    private boolean pendingAligned = false;
    private String dbgSleepReason = "n/a";

    /** 实测量统计(EWMA 均值 + 平均绝对偏差): 投影误差观测用。 */
    private static final class Stat {
        private static final double ALPHA = 0.02;
        private double mean = 0;
        private double mad = 0;
        private boolean init = false;

        void update(double x) {
            if (!init) {
                mean = x;
                mad = 0;
                init = true;
                return;
            }
            double d = x - mean;
            mean += ALPHA * d;
            mad = (1 - ALPHA) * (mad + ALPHA * Math.abs(d));
        }

        double mean() {
            return mean;
        }

        /** 平均绝对偏差, 作为离散度尺度使用。 */
        double sigma() {
            return mad;
        }

        boolean isInit() {
            return init;
        }
    }

    public ReflexScheduler() {
        this.gpuWeights = new float[gpuWindowSize];
        float weightSum = 0;

        for (int i = 0; i < gpuWindowSize; i++) {
            gpuWeights[i] = (float) Math.pow(weightBase, gpuWindowSize - 1 - i);
            weightSum += gpuWeights[i];
        }

        for (int i = 0; i < gpuWindowSize; i++) {
            gpuWeights[i] /= weightSum;
        }
    }

    public void updateCpuTime(long cpuTimeNs) {
        if (estimateCpuTime == null) {
            estimateCpuTime = cpuTimeNs;
        } else {
            estimateCpuTime = (long) (alpha * cpuTimeNs + (1 - alpha) * estimateCpuTime);
        }
    }

    public void updateSimTime(long simNs) {
        if (estimateSimTime == null) {
            estimateSimTime = simNs;
        } else {
            estimateSimTime = (long) (0.2 * simNs + 0.8 * estimateSimTime);
        }
    }

    public void updateSubmissionTime(long subNs) {
        if (estimateSubmissionTime == null) {
            estimateSubmissionTime = subNs;
        } else {
            estimateSubmissionTime = (long) (0.2 * subNs + 0.8 * estimateSubmissionTime);
        }
    }

    public void updateGpuTime(long gpuTimeNs) {
        gpuTimeRingBuffer[ringBufferIndex] = gpuTimeNs;
        ringBufferIndex = (ringBufferIndex + 1) % gpuWindowSize;
        validSamples = Math.min(validSamples + 1, gpuWindowSize);
    }

    public Long getEstimateGpuTime() {
        if (validSamples == 0) return null;

        float weightedSum = 0;
        for (int i = 0; i < validSamples; i++) {
            int idx = (ringBufferIndex - 1 - i + gpuWindowSize) % gpuWindowSize;
            weightedSum += gpuTimeRingBuffer[idx] * gpuWeights[i];
        }
        return (long) weightedSum;
    }

    /** 手动微调(可选): 用户在配置里给的固定等待偏置, 默认 0。正=提交更晚, 负=更早。 */
    public long getManualOffsetNs() {
        return ModConfig.INSTANCE.getManualSleepOffsetNs();
    }

    private static String ms(long ns) {
        return String.format(Locale.ROOT, "%.2f", ns / 1_000_000.0);
    }

    private static String msSigned(long ns) {
        if (ns == Long.MIN_VALUE) return "n/a";
        return String.format(Locale.ROOT, "%+.2f", ns / 1_000_000.0);
    }

    /**
     * 诊断用: 当前实际渲染分辨率 (帧缓冲像素尺寸)。
     * 窗口大小直接决定 GPU 负载, 是判读 est.gpu 是否合理的前提, 因此打进诊断行。
     */
    private static String frameBufferSize() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return "n/a";
            com.mojang.blaze3d.platform.Window w = mc.getWindow();
            if (w == null) return "n/a";
            return w.getWidth() + "x" + w.getHeight();
        } catch (Throwable t) {
            return "n/a";
        }
    }

    /**
     * 诊断信息: 把估计量、对齐状态与最近一次等待计算的全部中间量打成一行,
     * 便于还原"本次为什么等 0ms 还是 4ms"以及当前对齐质量。
     */
    public String getInternalsString() {
        Long estGpu = getEstimateGpuTime();
        return String.format(Locale.ROOT,
                "[Reflex Internals] cfg{%s} | res:%s | est={gpu:%s cpu:%s sim:%s sub:%s}ms | deque:%d gpuEndAge:%sms"
                        + " | align{err:%s lead:%s gpuFree:%s}"
                        + " | sleep{estFinishNow:%s -> sleep:%s [%s]}"
                        + " | deadband{os:%s}ms | projErr{%s mean:%s+/-%sms}",
                ModConfig.INSTANCE.toShortLogString(),
                frameBufferSize(),
                estGpu != null ? ms(estGpu) : "n/a",
                estimateCpuTime != null ? ms(estimateCpuTime) : "n/a",
                estimateSimTime != null ? ms(estimateSimTime) : "n/a",
                estimateSubmissionTime != null ? ms(estimateSubmissionTime) : "n/a",
                dbgDequeSize,
                dbgGpuEndAgeNs < 0 ? "n/a" : ms(dbgGpuEndAgeNs),
                msSigned(dbgAlignErrNs),
                ms(dbgLeadTimeNs),
                msSigned(dbgGpuFreeNs),
                msSigned(dbgEstFinishRelNs),
                msSigned(dbgSleepTimeNs),
                dbgSleepReason,
                ms(dbgOversleepDeadbandNs),
                dbgProjErrValid ? msSigned(dbgProjErrNs) : "n/a",
                projErrStat.isInit() ? msSigned((long) projErrStat.mean()) : "n/a",
                projErrStat.isInit() ? ms((long) projErrStat.sigma()) : "n/a");
    }

    public void checkCompletedQueries() {
        Iterator<GpuTimeCollector> iterator = gpuTimeCollectorDeque.iterator();
        while (iterator.hasNext()) {
            GpuTimeCollector col = iterator.next();
            if (col.startQueryInserted) {
                col.checkQuery();
                // 当 end 取到就判定该帧在 GPU 上彻底完成，处理并踢出队列
                if (col.endTimeSystem != null) {
                    processCompletedFrame(col);
                    iterator.remove();
                    collectorPool.returnObject(col);
                }
            } else {
                iterator.remove();
                collectorPool.returnObject(col);
            }
        }

        // Defensive cleanup: prevent unbounded queue growth if queries are dropped
        while (gpuTimeCollectorDeque.size() > 10) {
            GpuTimeCollector old = gpuTimeCollectorDeque.pollFirst();
            if (old != null) {
                collectorPool.returnObject(old);
            }
        }
    }

    // ===== 帧钩子(mixin 调用面) =====
    // 26+   : sleep() → startFrame → afterInputPoll → beforeSimulation → afterSimulation → beforeRenderSubmit → afterRenderSubmit → beforePresent → endFrame
    // pre-26: sleep() → startFrame(自身即 INPUT_SAMPLE) → beforeSimulation → afterSimulation → beforeRenderSubmit → ... → endFrame

    public void startFrame(long sleepReturnTime, long sleepNs) {
        currentFrameId++;
        dbgRecordedThisFrame = false; // 每帧允许记录一次"首次等待决策"
        GpuTimeCollector collector = collectorPool.borrow();
        collector.frameId = currentFrameId;
        collector.sleepReturnTime = sleepReturnTime;
        // pre-26 的 mixin 在记录 sleepReturnTime 之前已自行 glfwPollEvents(), 故此刻即输入采样点;
        // 26+ 的 pollEvents 在此之后才执行, 由 afterInputPoll() 覆盖成真实值。
        collector.inputSampleTime = sleepReturnTime;
        collector.sleepDurationNs = sleepNs;
        collector.projectedGpuFinishNs = pendingProjectionNs;
        pendingProjectionNs = null;
        collector.alignmentPerformed = pendingAligned;
        pendingAligned = false;
        gpuTimeCollectorDeque.addLast(collector);
        currentCollector = collector;
    }

    /** INPUT_SAMPLE: 输入事件轮询完成。仅 26+ 有独立注入点。 */
    public void afterInputPoll(long inputSampleTime) {
        if (currentCollector != null) {
            currentCollector.inputSampleTime = inputSampleTime;
        }
    }

    /** SIMULATION_START: 游戏逻辑(tick)开始。该帧不 tick 时不会被调用, 下游按 0 兜底。 */
    public void beforeSimulation() {
        if (currentCollector != null) {
            currentCollector.simulationStartTime = System.nanoTime();
        }
    }

    /**
     * SIMULATION_END: 游戏逻辑(tick)返回。
     * tick() 之后到 GameRenderer.render 之前还有纹理/音效/网络/输入累计等收尾,
     * 那段既不算模拟也不算提交, 所以必须单独打点才与 NVIDIA 的标记集对齐。
     */
    public void afterSimulation() {
        if (currentCollector != null) {
            currentCollector.simulationEndTime = System.nanoTime();
        }
    }

    public void beforeRenderSubmit() {
        if (currentCollector != null) {
            currentCollector.renderSubmitStartTime = System.nanoTime();
            if (!currentCollector.startQueryInserted) {
                currentCollector.startQueryInsert();
            }
        }
    }

    public void afterRenderSubmit() {
        if (currentCollector != null) {
            currentCollector.renderSubmitEndTime = System.nanoTime();
            currentCollector.endQueryInsert();
        }
    }

    public void beforePresent() {
        if (currentCollector != null) {
            currentCollector.presentStartTime = System.nanoTime();
            if (!currentCollector.endQueryInserted) {
                currentCollector.endQueryInsert();
            }
        }
    }

    public void endFrame(long presentEndTime) {
        if (currentCollector != null) {
            currentCollector.presentEndTime = presentEndTime;
            long pureCpuTime = (currentCollector.renderSubmitEndTime > 0)
                    ? Math.max(0, currentCollector.renderSubmitEndTime - currentCollector.sleepReturnTime)
                    : Math.max(0, presentEndTime - currentCollector.sleepReturnTime);
            updateCpuTime(pureCpuTime);
            currentCollector = null;
        }
        checkCompletedQueries();
    }

    private void processCompletedFrame(GpuTimeCollector col) {
        long pureCpuDuration = (col.renderSubmitEndTime > 0)
                ? Math.max(0, col.renderSubmitEndTime - col.sleepReturnTime)
                : Math.max(0, col.presentEndTime - col.sleepReturnTime);
        long gpuDuration = Math.max(0, col.endTimeSystem - col.startTimeSystem);

        // 抗离群钳制: 单帧毛刺(着色器编译/驱动卡顿/其他进程抢占)不应把 estGpu 抬走。
        // 数值依据: weightBase=1.5 使最新帧权重高达 0.333, 一个 146ms 毛刺帧曾把 estGpu
        // 从 17.2ms 抬到 60.1ms(+250%)。参照值取【更新前】的估计值, 钳制比例 2x:
        // 真实负载 2 倍跳变一步到位, 更大的跳变 2 帧内跟上; 单帧离群最多带偏 +33%。
        Long gpuRefBefore = getEstimateGpuTime();
        long boundedGpu = gpuDuration;
        if (gpuRefBefore != null && gpuRefBefore > 0L) {
            boundedGpu = Math.max(gpuRefBefore / 2, Math.min(gpuRefBefore * 2, gpuDuration));
        }
        updateGpuTime(boundedGpu);

        // 投影误差观测: 本帧等待时曾预测过"GPU 何时空闲", 现在已有本帧 GPU 实测完成时刻, 直接比对。
        // 新控制律下该投影就是 gpuFree 的锚, 所以这个偏差直接反映"提交结束会落在 GPU 变闲时刻的哪一侧"。
        if (col.projectedGpuFinishNs != null && col.endTimeSystem != null) {
            long projErr = col.endTimeSystem - col.projectedGpuFinishNs;
            projErrStat.update(projErr);
            dbgProjErrNs = projErr;
            dbgProjErrValid = true;
        } else {
            dbgProjErrValid = false;
        }

        // Simulation duration (from pollEvents to start of GameRenderer.render)
        long simDuration = (col.renderSubmitStartTime > 0 && col.renderSubmitStartTime > col.sleepReturnTime)
                ? (col.renderSubmitStartTime - col.sleepReturnTime)
                : 0L;
        updateSimTime(simDuration);

        // Submission duration (GameRenderer.render draw call recording)
        long subDuration = (col.renderSubmitEndTime > 0 && col.renderSubmitStartTime > 0 && col.renderSubmitEndTime > col.renderSubmitStartTime)
                ? (col.renderSubmitEndTime - col.renderSubmitStartTime)
                : Math.max(0, pureCpuDuration - simDuration);
        updateSubmissionTime(subDuration);

        // 对齐误差 = renderSubmitEnd − 上一帧 gpuRenderEnd, 符号原样保留。
        //   = 0 完美对齐; >0 提交晚了(GPU 在等指令); <0 提交早了(指令先入队, 有积压)
        // 用 renderSubmitEnd 是因为控制律控的就是它: wake = gpuFree − lead, 而 lead = estSim + estSub
        // 恰好量到 renderSubmitEnd 为止, 故 renderSubmitEnd = gpuFree + offset。
        // 本帧没做过对齐就不报数: 调度器没参与摆放这一帧, 差值只是游戏自然节拍的结果。
        long alignErrNs = Long.MIN_VALUE;
        if (col.alignmentPerformed && lastFrameGpuEndTimeSystem != null) {
            alignErrNs = col.renderSubmitEndTime - lastFrameGpuEndTimeSystem;
        }
        dbgAlignErrNs = alignErrNs;

        // 帧间空转(Oversleep)观测: GPU 上一帧结束 → 本帧开工 之间白等了多久。
        // 只统计确实因 Reflex 等待引起的部分(min(sleep, idle)), 避免把 CPU 逻辑负载等非休眠原因算进来。
        long gpuIdleGap = (lastFrameGpuEndTimeSystem != null && col.startTimeSystem != null)
                ? Math.max(0L, col.startTimeSystem - lastFrameGpuEndTimeSystem)
                : 0L;
        long oversleepNs = Math.min(col.sleepDurationNs, gpuIdleGap);

        Long estGpu = getEstimateGpuTime();
        long oversleepDeadbandNs = (estGpu != null) ? (long) (FRAC_OVERSLEEP_DEADBAND * estGpu) : 0L;
        dbgOversleepDeadbandNs = oversleepDeadbandNs;

        boolean didSleep = col.sleepDurationNs > 0L;
        boolean isStarved = didSleep && (oversleepNs > oversleepDeadbandNs);

        lastFrameGpuEndTimeSystem = col.endTimeSystem;

        ReflexMetrics.getInstance().recordFrame(
                col,
                pureCpuDuration,
                oversleepNs,
                boundedGpu,
                col.sleepDurationNs,
                alignErrNs,
                isStarved);
    }

    /** 关闭状态下把等待诊断量显式清空, 避免 Internals 显示冻结的旧值(且 reason 仍是 sleep)。 */
    private void markSleepDebugDisabled() {
        dbgRecordedThisFrame = true;
        pendingProjectionNs = null;
        // 早退路径不经过 sleep() 末尾的赋值, 必须在这里显式清零, 否则本帧会继承上一帧的 true
        pendingAligned = false;
        dbgLeadTimeNs = 0L;
        dbgEstFinishRelNs = Long.MIN_VALUE;
        dbgSleepTimeNs = 0L;
        dbgDequeSize = gpuTimeCollectorDeque.size();
        dbgSleepReason = "disabled";
    }

    /**
     * 记录本帧"首次"等待计算的中间量(仅诊断用)。
     * 只记首帧: sleep() 每 1ms 会重算一次, 若不拦, 记到的是循环末尾的剩余量(通常 <1ms),
     * 会让人误以为调度器只等了这么点, 掩盖真正的首次对齐决策。
     *
     * @param now 决策时刻本身, 必须与 estFinishRel 用的是同一次 nanoTime。
     *            早先这里自己再读一次 nanoTime, 于是 dbgGpuFreeNs = now₂ + (gpuFree − now₁)
     *            = gpuFree + (now₂ − now₁), 凭空多出几微秒的假偏移。
     */
    private void recordSleepDebug(long now, long lead, long estFinishRel, long sleepTime, String reason) {
        if (dbgRecordedThisFrame) {
            return;
        }
        dbgRecordedThisFrame = true;
        // 暂存本次投影, 供帧末与 GPU 实测完成时刻比对(投影误差观测, 不参与控制)
        pendingProjectionNs = (estFinishRel == Long.MIN_VALUE) ? null : (now + estFinishRel);
        dbgLeadTimeNs = lead;
        dbgEstFinishRelNs = estFinishRel;
        dbgGpuFreeNs = (estFinishRel == Long.MIN_VALUE) ? Long.MIN_VALUE : (now + estFinishRel);
        dbgSleepTimeNs = sleepTime;
        dbgDequeSize = gpuTimeCollectorDeque.size();
        dbgGpuEndAgeNs = (lastFrameGpuEndTimeSystem != null) ? (now - lastFrameGpuEndTimeSystem) : -1L;
        dbgSleepReason = reason;
    }

    /**
     * 唯一的控制律: renderSubmitEnd 对齐上一帧 gpuRenderEnd。
     *
     * CPU 从醒来到提交结束需要 lead = sim + sub(均为实测 EWMA), 所以
     *   醒来时刻 = GPU 变闲时刻 − lead
     * "GPU 变闲时刻" = 最后一帧的【预期完成时刻】, 记作 gpuFree。锚点是已测的上一帧
     * gpuRenderEnd, 再按 deque 里每个在途帧各加一帧 estGpu 投影出来:
     *   gpuFree = 上一帧已测 GPU 结束 (+ 每个在途帧一个 estGpu)
     * 已开工的在途帧改用它【实测】的开工时刻 + estGpu(事实值优于估计链)。
     * 队列为空时 gpuFree 就等于上一帧的实测完成时刻, 不凭空多算一帧 —— 否则调度器会对着
     * 一台已经空闲的显卡继续等(锁帧场景下 Sleep ≈ Oversleep 即源于此)。
     * 目标每帧用新的实测重新锚定, 相位不累积。
     *
     * 这个对齐在结构上消除了帧内饥饿: 提交结束时整条指令流已就绪,
     * GPU 变闲的瞬间即可拿到完整帧, "追到提交前沿"不可能发生。
     */
    private Long calculateSleepTime() {
        if (!ModConfig.INSTANCE.isReflexEnabled()) {
            dbgSleepReason = "disabled";
            return null;
        }
        Long estGpu = getEstimateGpuTime();
        if (estGpu == null || estimateSimTime == null || estimateSubmissionTime == null) {
            dbgSleepReason = "no-estimate";
            return null;
        }

        long now = System.nanoTime();
        long lead = estimateSimTime + estimateSubmissionTime;
        if (lead >= estGpu) {
            // CPU 自身工作量(sim+sub)已不小于 GPU 单帧工作量: 对齐不可能, 全速运行
            recordSleepDebug(now, lead, Long.MIN_VALUE, 0L, "CPU-bound");
            return null;
        }

        // gpuFree = 最后一帧的【预期 GPU 完成时刻】。起点就是已测的上一帧完成时刻, 不预先加一帧
        // 工作量: 队列为空时上一帧画完显卡就真的闲了, 多加一个 estGpu 会让调度器对着一台已经空闲
        // 的显卡继续等 —— 锁帧场景下 Sleep ≈ Oversleep 就是这么来的。
        Long gpuFree = lastFrameGpuEndTimeSystem;

        // deque 精化: 已开工帧用【实测】开工时刻 + estGpu(事实值优于估计链);
        // 待开工帧(已提交未开工)排在队尾, 依次 +estGpu
        for (GpuTimeCollector c : gpuTimeCollectorDeque) {
            if (c.startTimeSystem != null && c.startTimeSystem > 0) {
                gpuFree = c.startTimeSystem + estGpu;
            } else if (gpuFree != null) {
                gpuFree = gpuFree + estGpu;
            }
        }

        if (gpuFree == null) {
            // 一次 GPU 完成时刻都还没测到, 没有锚点。不猜, 本帧不做对齐。
            recordSleepDebug(now, lead, Long.MIN_VALUE, 0L, "no-anchor");
            return null;
        }

        long sleepTime = gpuFree - lead + getManualOffsetNs() - now;

        recordSleepDebug(now, lead, gpuFree - now, sleepTime,
                sleepTime > 50_000L ? "sleep" : "too-short");

        if (sleepTime > 50_000L) { // Only sleep if > 0.05ms
            return sleepTime;
        }
        return null;
    }

    public long sleep() {
        if (!ModConfig.INSTANCE.isReflexEnabled()) {
            checkCompletedQueries();
            // 注意: 此处提前 return, 不会走到 calculateSleepTime, 因此必须在这里把诊断量
            // 显式标成 disabled —— 否则 Internals 会一直显示上一帧的冻结值(且 reason 仍是 sleep),
            // 让人误以为关闭后仍有等待决策。
            markSleepDebugDisabled();
            return 0L;
        }

        long startSleep = System.nanoTime();
        boolean aligned = false;

        // 采用轮询方式，每次睡1ms，因为gpuTimeCollectorDeque随时可能更新
        while (true) {
            // 计算 sleep 前，先更新一遍gpuTimeCollectorDeque
            checkCompletedQueries();

            Long sleepTime = calculateSleepTime();
            if (sleepTime == null || sleepTime <= 0) {
                break;
            }
            // 拿到过正目标 = 本帧的提交结束时刻确实是被调度器摆出来的。
            // 之后走自旋还是停车不影响这个判定, 两者都在逼近同一目标。
            aligned = true;

            // 剩余等待时间不大于一个休眠粒度时, 停车必定过冲, 改为精确自旋到目标点
            // (自旋阈值 = 休眠粒度 + 实测停车过冲: 过冲大就多自旋, 保证末段精度)
            long spinThresholdNs = PARK_QUANTUM_NS + (long) parkOvershootNs;
            if (sleepTime <= spinThresholdNs) {
                long targetTime = System.nanoTime() + sleepTime;
                while (System.nanoTime() < targetTime) {
                    Thread.onSpinWait();
                }
                break;
            }

            // 采用轮询方式，每次睡一个粒度，随后重新 checkCompletedQueries() 并计算
            long parkStart = System.nanoTime();
            LockSupport.parkNanos(PARK_QUANTUM_NS);
            // 实测本次停车过冲(EMA), 供上面的自旋阈值自适应
            double over = Math.max(0.0, (System.nanoTime() - parkStart - PARK_QUANTUM_NS) / 1e6);
            parkOvershootNs = parkSeen ? (parkOvershootNs * 0.75 + over * 0.25) : over;
            parkSeen = true;
        }

        pendingAligned = aligned;
        return System.nanoTime() - startSleep;
    }
}
