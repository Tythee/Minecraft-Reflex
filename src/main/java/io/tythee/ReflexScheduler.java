package io.tythee;

import io.tythee.config.ModConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

public class ReflexScheduler {
    private final float alpha = 0.85f;
    private Long estimateCpuTime = null;
    private Long estimateSimTime = null;
    private Long estimateSubmissionTime = null;
    private Long estimateFirstBatchFlushNs = null;
    private Long estimateOverlapTime = null;

    private final int gpuWindowSize = 60;
    private final long[] gpuTimeRingBuffer = new long[gpuWindowSize];
    private int ringBufferIndex = 0;
    private int validSamples = 0;

    public Deque<GpuTimeCollector> gpuTimeCollectorDeque = new ArrayDeque<>();
    private GpuTimeCollector currentCollector = null;

    private final ObjectPool<GpuTimeCollector> collectorPool = new ObjectPool<>(
            GpuTimeCollector::new,
            GpuTimeCollector::reset
    );

    private final float weightBase = 1.5f;
    private final float[] gpuWeights;

    // Adaptive closed-loop margin state
    private long adaptiveOffsetNs = 0L; // default 0.00ms, range [-5ms, +5ms]
    private long currentFrameId = 0L;
    private long lastAdjustedTargetFrameId = 0L;
    private Long lastFrameGpuEndTimeSystem = null;
    private Long lastFrameCpuEndTime = null;

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

    public void updateFirstBatchFlush(long flushNs) {
        long cap = (estimateSubmissionTime != null && estimateSubmissionTime > 0)
                ? estimateSubmissionTime
                : 3_000_000L;
        long clamped = Math.min(flushNs, cap);
        if (estimateFirstBatchFlushNs == null) {
            estimateFirstBatchFlushNs = clamped;
        } else {
            estimateFirstBatchFlushNs = (long) (0.15 * clamped + 0.85 * estimateFirstBatchFlushNs);
        }
    }

    public long getEffectiveFirstBatchFlushNs() {
        if (estimateFirstBatchFlushNs != null) {
            if (estimateSubmissionTime != null && estimateSubmissionTime > 0) {
                return Math.min(estimateFirstBatchFlushNs, estimateSubmissionTime);
            }
            return estimateFirstBatchFlushNs;
        }
        if (estimateSubmissionTime != null && estimateSubmissionTime > 0) {
            return Math.min(Math.max(300_000L, (long) (0.35 * estimateSubmissionTime)), estimateSubmissionTime);
        }
        return 600_000L; // default 0.60ms
    }

    public void updateGpuTime(long gpuTimeNs) {
        gpuTimeRingBuffer[ringBufferIndex] = gpuTimeNs;
        ringBufferIndex = (ringBufferIndex + 1) % gpuWindowSize;
        validSamples = Math.min(validSamples + 1, gpuWindowSize);
    }

    public void updateOverlapTime(long overlapNs) {
        if (estimateOverlapTime == null) {
            estimateOverlapTime = overlapNs;
        } else {
            estimateOverlapTime = (long) (0.2 * overlapNs + 0.8 * estimateOverlapTime);
        }
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

    public long getEffectiveOffsetNs() {
        if (ModConfig.INSTANCE.isAdaptiveOffset()) {
            return adaptiveOffsetNs;
        }
        return ModConfig.INSTANCE.getManualWaitOffsetNs();
    }

    public long getEffectiveSafetyMarginNs() {
        return getEffectiveOffsetNs();
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

    private void processCompletedFrame(GpuTimeCollector col) {
        long pureCpuDuration = (col.renderBuildEndTime > 0)
                ? Math.max(0, col.renderBuildEndTime - col.cpuStartTime)
                : Math.max(0, col.cpuEndTime - col.cpuStartTime);
        long gpuDuration = Math.max(0, col.endTimeSystem - col.startTimeSystem);
        updateGpuTime(gpuDuration);

        // Simulation duration (from pollEvents to start of GameRenderer.render)
        long simDuration = (col.renderBuildStartTime > 0 && col.renderBuildStartTime > col.cpuStartTime)
                ? (col.renderBuildStartTime - col.cpuStartTime)
                : 0L;
        updateSimTime(simDuration);

        // Submission duration (GameRenderer.render draw call recording)
        long subDuration = (col.renderBuildEndTime > 0 && col.renderBuildStartTime > 0 && col.renderBuildEndTime > col.renderBuildStartTime)
                ? (col.renderBuildEndTime - col.renderBuildStartTime)
                : Math.max(0, pureCpuDuration - simDuration);
        updateSubmissionTime(subDuration);

        // --- Hybrid Architecture: Approach 1 + Approach 3 for First Batch Flush ---
        // Approach 3 (Dynamic Baseline Ratio): 35% of submission duration as cold-start fallback
        long baselineFlushNs = (subDuration > 0) ? (long) (0.35 * subDuration) : 600_000L;

        // Approach 1 (Online Hardware Calibration on True 0-Queue Frame)
        // Must ensure GPU already finished previous frame BEFORE this frame's submission started,
        // otherwise gpuStart is gated by previous frame's completion and includes queue delay!
        boolean trueZeroQueue = col.startTimeSystem != null
                && lastFrameGpuEndTimeSystem != null
                && col.renderBuildStartTime > 0
                && col.renderBuildStartTime >= (lastFrameGpuEndTimeSystem - 100_000L);

        if (trueZeroQueue && col.startTimeSystem >= col.renderBuildStartTime) {
            long sampleFlushNs = col.startTimeSystem - col.renderBuildStartTime;
            if (sampleFlushNs > 50_000L && sampleFlushNs <= subDuration) {
                updateFirstBatchFlush(sampleFlushNs);
            }
        } else if (estimateFirstBatchFlushNs == null) {
            // Cold-start fallback using Approach 3
            estimateFirstBatchFlushNs = baselineFlushNs;
        }

        // Potential Overlap: submission time minus first batch flush delay
        long flushDelay = getEffectiveFirstBatchFlushNs();
        long overlapNs = Math.max(0, subDuration - flushDelay);
        overlapNs = Math.min(overlapNs, gpuDuration);
        updateOverlapTime(overlapNs);

        Long estGpu = getEstimateGpuTime();
        boolean isGpuBound = estGpu != null && estimateCpuTime != null && estGpu > estimateCpuTime;

        // 睡过头多少时间 (Oversleep / GPU 空转时长): 显卡在两帧之间的闲置间隙
        long oversleepNs = (lastFrameGpuEndTimeSystem != null && col.startTimeSystem != null)
                ? Math.max(0L, col.startTimeSystem - lastFrameGpuEndTimeSystem)
                : 0L;

        // 首批指令到达驱动/GPU的预估时刻
        long estFlushPoint = (col.renderBuildStartTime > 0)
                ? (col.renderBuildStartTime + flushDelay)
                : (col.cpuStartTime + simDuration + flushDelay);

        // 指令在驱动队列排队时长 (允许为负: 因为 Flush 为估计值，负数表示 GPU 在预估 Flush 之前就已开工，排队时长越小越好)
        long queueNs = (col.startTimeSystem != null)
                ? (col.startTimeSystem - estFlushPoint)
                : 0L;

        boolean didWait = col.waitDurationNs > 100_000L;
        boolean isCpuSpike = (estimateCpuTime != null && (pureCpuDuration - estimateCpuTime > 1_500_000L || pureCpuDuration > 1.30 * estimateCpuTime));
        boolean isStarved = isGpuBound && didWait && (oversleepNs > 200_000L) && !isCpuSpike;

        // Feedback closed-loop: evaluate per frame, but only when:
        // 1. We are in GPU-bound mode
        // 2. Reflex actually executed a wait on this frame (didWait == true), so timing is governed by Reflex!
        // 3. Previous adjustment's feedback has arrived (col.frameId >= lastAdjustedTargetFrameId)
        if (ModConfig.INSTANCE.isAdaptiveOffset() && isGpuBound && didWait && lastFrameGpuEndTimeSystem != null && col.frameId >= lastAdjustedTargetFrameId) {
            boolean hasQueue = queueNs > 150_000L;
            boolean isOversleep = (oversleepNs > 200_000L) && !hasQueue;

            if (isCpuSpike) {
                if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
                    ReflexClient.LOGGER.info(String.format(
                            Locale.ROOT,
                            "[Reflex Adaptive] GPU idle ignored due to CPU spike (cpu=%.2fms, avg=%.2fms). Offset preserved: %+.3fms",
                            pureCpuDuration / 1_000_000.0,
                            (estimateCpuTime != null ? estimateCpuTime : pureCpuDuration) / 1_000_000.0,
                            adaptiveOffsetNs / 1_000_000.0));
                }
                lastAdjustedTargetFrameId = currentFrameId + 1;
            } else if (isOversleep) {
                // 睡过头了 (显卡空转且未积压指令): 超过物理硬件底线 (0.2ms)，说明睡太久了！下调 Offset (减少等待时间)，压低睡过头时间
                isStarved = true;
                long prevOffset = adaptiveOffsetNs;
                long stepDown = Math.min(100_000L, Math.max(20_000L, oversleepNs / 2));
                adaptiveOffsetNs = Math.max(-5_000_000L, adaptiveOffsetNs - stepDown);
                lastAdjustedTargetFrameId = currentFrameId + 1;

                if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
                    ReflexClient.LOGGER.info(String.format(
                            Locale.ROOT,
                            "[Reflex Adaptive] Oversleep detected (oversleep=%.2fms, wait=%.2fms, frame=#%d). Offset adjusted DOWN: -%.3fms -> %+.3fms (Next eval: Frame #%d)",
                            oversleepNs / 1_000_000.0,
                            col.waitDurationNs / 1_000_000.0,
                            col.frameId,
                            (prevOffset - adaptiveOffsetNs) / 1_000_000.0,
                            adaptiveOffsetNs / 1_000_000.0,
                            lastAdjustedTargetFrameId));
                }
            } else if (queueNs > 100_000L) {
                // 队列积压: 指令在驱动积压等待，queue指标越小越好！上调 Offset (增加等待时间)，将 Queue 压向零或负数
                long prevOffset = adaptiveOffsetNs;
                long stepUp;
                if (queueNs > 1_000_000L) {
                    stepUp = 50_000L; // fast drain for queue backlog > 1.0ms
                } else if (queueNs > 300_000L) {
                    stepUp = 25_000L; // moderate drain for queue backlog > 0.3ms
                } else {
                    stepUp = 10_000L; // fine tuning towards <= 0.1ms
                }
                adaptiveOffsetNs = Math.min(5_000_000L, adaptiveOffsetNs + stepUp);
                lastAdjustedTargetFrameId = currentFrameId + 1;

                if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
                    ReflexClient.LOGGER.info(String.format(
                            Locale.ROOT,
                            "[Reflex Adaptive] Queue backlog (Q=%+.2fms, oversleep=%.2fms, frame=#%d). Offset adjusted UP: +%.3fms -> %+.3fms (Next eval: Frame #%d)",
                            queueNs / 1_000_000.0,
                            oversleepNs / 1_000_000.0,
                            col.frameId,
                            (adaptiveOffsetNs - prevOffset) / 1_000_000.0,
                            adaptiveOffsetNs / 1_000_000.0,
                            lastAdjustedTargetFrameId));
                }
            } else {
                // 黄金平衡态: Oversleep处于硬件底线内(<=0.2ms)且Queue已最小化(<=0.1ms或为负)，维持当前Offset
                lastAdjustedTargetFrameId = currentFrameId + 1;
            }
        }
        lastFrameGpuEndTimeSystem = col.endTimeSystem;

        ReflexMetrics.getInstance().recordFrame(
                col,
                pureCpuDuration,
                simDuration,
                subDuration,
                flushDelay,
                queueNs,
                oversleepNs,
                gpuDuration,
                overlapNs,
                col.waitDurationNs,
                getEffectiveOffsetNs(),
                isStarved
        );
    }

    private Long calculateWaitTime() {
        if (!ModConfig.INSTANCE.isReflexEnabled()) {
            return null;
        }
        Long estGpu = getEstimateGpuTime();
        if (estGpu == null || estimateCpuTime == null) {
            return null;
        }

        // Modernized lead time calculation:
        // leadTime = T_simulation + Delta_t_first_batch_flush
        long simTime = (estimateSimTime != null)
                ? estimateSimTime
                : Math.max(0, estimateCpuTime - (estimateSubmissionTime != null ? estimateSubmissionTime : 0L));
        long flushDelay = getEffectiveFirstBatchFlushNs();
        long leadTime = simTime + flushDelay;
        long offset = getEffectiveOffsetNs();

        // If CPU work alone minus offset is longer than or equal to GPU frame time, we are CPU-bound.
        // No queue buildup can happen in front of the GPU; never sleep.
        if (leadTime - offset >= estGpu) {
            return null;
        }

        long now = System.nanoTime();

        // 初始预估gpu完成时间点为上一帧的end
        long estGpuFinish = (lastFrameGpuEndTimeSystem != null)
                ? lastFrameGpuEndTimeSystem
                : now;

        // 对gpuTimeCollectorDeque进行遍历更新初始预估gpu完成时间点
        for (GpuTimeCollector col : gpuTimeCollectorDeque) {
            // 对每个GpuTimeCollector来说，如果有GPU_start时间戳，用GPU_start+estGpu更新预估gpu完成时间点
            if (col.startTimeSystem != null && col.startTimeSystem > 0) {
                estGpuFinish = col.startTimeSystem + estGpu;
            } else {
                // 没有的话，根据预估gpu完成时间点以及自身flush点处理
                long flushPoint = (col.renderBuildStartTime > 0)
                        ? (col.renderBuildStartTime + flushDelay)
                        : (col.cpuStartTime > 0 ? col.cpuStartTime + leadTime : now);

                // 如果flush点在预估gpu完成时间点之前,预估gpu完成时间点增加一个estGpu，否则更新为flush点+estGpu
                if (flushPoint < estGpuFinish) {
                    estGpuFinish = estGpuFinish + estGpu;
                } else {
                    estGpuFinish = flushPoint + estGpu;
                }
            }
        }

        // 最后用得出的预估gpu完成时间点计算wait
        long targetPollTime = estGpuFinish - leadTime + offset;
        long waitTime = targetPollTime - now;

        // Hard upper bound: wait time can never exceed the total GPU work ahead, capped at 3 frames
        long maxWait = (long) Math.min(3, Math.max(1, gpuTimeCollectorDeque.size())) * estGpu;
        if (waitTime > maxWait) {
            waitTime = maxWait;
        }

        if (waitTime > 50_000L) { // Only sleep if > 0.05ms
            return waitTime;
        }
        return null;
    }

    public long Wait() {
        if (!ModConfig.INSTANCE.isReflexEnabled()) {
            checkCompletedQueries();
            return 0L;
        }

        long startWait = System.nanoTime();

        // 采用轮询方式，每次睡1ms，因为gpuTimeCollectorDeque随时可能更新
        while (true) {
            // 计算wait前，先更新一遍gpuTimeCollectorDeque
            checkCompletedQueries();

            Long waitTime = calculateWaitTime();
            if (waitTime == null || waitTime <= 0) {
                break;
            }

            // 若剩余等待时间在精细自旋阈值内 (<= 1.2ms)，精确自旋到目标点并退出
            if (waitTime <= 1_200_000L) {
                long targetTime = System.nanoTime() + waitTime;
                while (System.nanoTime() < targetTime) {
                    Thread.onSpinWait();
                }
                break;
            }

            // 采用轮询方式，每次睡 1ms，随后重新 checkCompletedQueries() 并计算
            LockSupport.parkNanos(1_000_000L); // 1ms
        }

        return System.nanoTime() - startWait;
    }

    public void startFrame(long cpuStartTime, long waitNs) {
        currentFrameId++;
        GpuTimeCollector collector = collectorPool.borrow();
        collector.frameId = currentFrameId;
        collector.cpuStartTime = cpuStartTime;
        collector.waitDurationNs = waitNs;
        gpuTimeCollectorDeque.addLast(collector);
        currentCollector = collector;
    }

    public void beforeRenderBuild() {
        if (currentCollector != null) {
            currentCollector.renderBuildStartTime = System.nanoTime();
            if (!currentCollector.startQueryInserted) {
                currentCollector.startQueryInsert();
            }
        }
    }

    public void afterRenderBuild() {
        if (currentCollector != null) {
            currentCollector.renderBuildEndTime = System.nanoTime();
            currentCollector.endQueryInsert();
        }
    }

    public void beforePresent() {
        if (currentCollector != null) {
            currentCollector.renderSubmitEndTime = System.nanoTime();
            if (!currentCollector.endQueryInserted) {
                currentCollector.endQueryInsert();
            }
        }
    }

    public void endFrame(long cpuEndTime) {
        lastFrameCpuEndTime = cpuEndTime;
        if (currentCollector != null) {
            currentCollector.cpuEndTime = cpuEndTime;
            long pureCpuTime = (currentCollector.renderBuildEndTime > 0)
                    ? Math.max(0, currentCollector.renderBuildEndTime - currentCollector.cpuStartTime)
                    : Math.max(0, cpuEndTime - currentCollector.cpuStartTime);
            updateCpuTime(pureCpuTime);
            currentCollector = null;
        }
        checkCompletedQueries();
    }
}
