package io.tythee;

import io.tythee.config.ModConfig;
import static io.tythee.ReflexClient.LOGGER;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ReflexMetrics {
    private static final ReflexMetrics INSTANCE = new ReflexMetrics();

    public static ReflexMetrics getInstance() {
        return INSTANCE;
    }

    /** 本帧实测对齐误差; Long.MIN_VALUE = 无上一帧 GPU 完成时刻可参照。 */
    private long alignErrNs = 0;

    private double smoothGameMs = 0;
    private double smoothOversleepMs = 0;
    private double smoothRenderMs = 0;
    private double smoothSleepMs = 0;
    private double smoothPcMs = 0;
    private double smoothAlignErrMs = 0;

    private int frameCount = 0;
    private int starveCount = 0;
    private long lastLogTimeNs = System.nanoTime();
    /** 上次检查 config 文件修改时间的时刻 (4Hz 热重载用)。 */
    private long lastCfgCheckNs = 0L;
    private int framesSinceLastLog = 0;

    private long lastSleepReturnTime = 0;
    /** INPUT_SAMPLE: 输入轮询完成时刻。 */
    private long lastInputSampleTime = 0;
    /** SIMULATION_START: Minecraft.tick() 入口时刻。 */
    private long lastSimulationStartTime = 0;
    /** SIMULATION_END: Minecraft.tick() 返回时刻。 */
    private long lastSimulationEndTime = 0;
    private long lastRenderSubmitStartTime = 0;
    private long lastRenderSubmitEndTime = 0;
    private long lastPresentStartTime = 0;
    private long lastPresentEndTime = 0;
    private long lastGpuStartTimeSystem = 0;
    private long lastGpuEndTimeSystem = 0;
    private long lastClockOffset = 0;
    private long lastOversleepNs = 0;
    /** 上一帧的 GPU 完成时刻, Last 行的单根 | 用它。 */
    private long prevGpuEndTimeSystem = 0;
    /** AlignErr 需要一个已测的上一帧 GPU 完成时刻才有效; 无效时不参与平滑。 */
    private boolean alignErrValid = false;

    // 仅用于 F3/日志的显示平滑, 不参与任何控制回路, 因此不随硬件/帧率标定
    private static final float ALPHA = 0.15f;

    public synchronized void recordFrame(
            GpuTimeCollector col,
            long gameNs,
            long oversleepNs,
            long renderNs,
            long sleepNs,
            long alignErrNs,
            boolean starved) {

        long truePcNs = (col != null && col.endTimeSystem != null && col.endTimeSystem > 0 && col.sleepReturnTime > 0)
                ? Math.max(0, col.endTimeSystem - col.sleepReturnTime)
                : Math.max(0, gameNs + renderNs);

        if (col != null) {
            // 先存上一帧的 GPU 完成时刻(Last 行的 | 用), 再被本帧覆盖
            this.prevGpuEndTimeSystem = this.lastGpuEndTimeSystem;
            this.lastSleepReturnTime = col.sleepReturnTime;
            this.lastInputSampleTime = (col.inputSampleTime > 0) ? col.inputSampleTime : col.sleepReturnTime;
            // 未 tick 的帧(菜单/加载)没有模拟起点, 退回输入采样点, 让 Sim 段仍有可见长度
            this.lastSimulationStartTime = (col.simulationStartTime > 0)
                    ? col.simulationStartTime : this.lastInputSampleTime;
            // 未 tick 时退回模拟起点, 使 Simulation 段长度为 0, 整段都算到 →RenderSubmit 空档里
            this.lastSimulationEndTime = (col.simulationEndTime > 0)
                    ? col.simulationEndTime : this.lastSimulationStartTime;
            this.lastRenderSubmitStartTime = (col.renderSubmitStartTime > 0) ? col.renderSubmitStartTime : col.sleepReturnTime;
            this.lastRenderSubmitEndTime = (col.renderSubmitEndTime > 0) ? col.renderSubmitEndTime : col.presentEndTime;
            this.lastPresentStartTime = (col.presentStartTime > 0) ? col.presentStartTime : col.presentEndTime;
            this.lastPresentEndTime = col.presentEndTime;
            this.lastGpuStartTimeSystem = (col.startTimeSystem != null) ? col.startTimeSystem : 0;
            this.lastGpuEndTimeSystem = (col.endTimeSystem != null) ? col.endTimeSystem : 0;
            this.lastClockOffset = col.clockOffset;
            this.lastOversleepNs = oversleepNs;
        }

        this.alignErrNs = alignErrNs;

        double gameMs = gameNs / 1_000_000.0;
        double oversleepMsVal = oversleepNs / 1_000_000.0;
        double renderMs = renderNs / 1_000_000.0;
        double sleepMsVal = sleepNs / 1_000_000.0;
        double pcMs = truePcNs / 1_000_000.0;
        // Long.MIN_VALUE 是"本帧没有可参照的上一帧 GPU 完成时刻"的哨兵, 不是测量值。
        // 直接除 1e6 会得到约 -9.2e12ms 并折进 EMA, 之后要数百帧才衰减掉, 所以显式跳过。
        boolean alignErrOk = alignErrNs != Long.MIN_VALUE;
        this.alignErrValid = alignErrOk;
        double alignErrMsVal = alignErrNs / 1_000_000.0;

        if (frameCount == 0) {
            smoothGameMs = gameMs;
            smoothOversleepMs = oversleepMsVal;
            smoothRenderMs = renderMs;
            smoothSleepMs = sleepMsVal;
            smoothPcMs = pcMs;
            if (alignErrOk) {
                smoothAlignErrMs = alignErrMsVal;
            }
        } else {
            smoothGameMs = ALPHA * gameMs + (1 - ALPHA) * smoothGameMs;
            smoothOversleepMs = ALPHA * oversleepMsVal + (1 - ALPHA) * smoothOversleepMs;
            smoothRenderMs = ALPHA * renderMs + (1 - ALPHA) * smoothRenderMs;
            smoothSleepMs = ALPHA * sleepMsVal + (1 - ALPHA) * smoothSleepMs;
            smoothPcMs = ALPHA * pcMs + (1 - ALPHA) * smoothPcMs;
            if (alignErrOk) {
                smoothAlignErrMs = ALPHA * alignErrMsVal + (1 - ALPHA) * smoothAlignErrMs;
            }
        }

        frameCount++;
        framesSinceLastLog++;
        if (starved) {
            starveCount++;
        }

        long now = System.nanoTime();
        // 配置热重载检查: 4Hz。诊断日志是 1Hz, 但热重载单独用更高频 —— 段长很短(秒级)的
        // 自动化测试里, "标记 -> 落盘 -> 生效"的滞后会直接吃掉可观比例的采样, 所以取 250ms。
        // mtime 检查是每秒 4 次系统调用, 不在逐帧热路径的量级上。
        if (now - lastCfgCheckNs >= 250_000_000L) {
            lastCfgCheckNs = now;
            ModConfig.reloadIfChanged();
        }
        if (now - lastLogTimeNs >= 1_000_000_000L) {
            if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
                double elapsedSec = (now - lastLogTimeNs) / 1_000_000_000.0;
                double fps = framesSinceLastLog / elapsedSec;
                String mode = (smoothRenderMs >= smoothGameMs) ? "GPU-Bound" : "CPU-Bound";
                LOGGER.info(String.format(
                        Locale.ROOT,
                        "[Reflex Summary] FPS: %.1f | PC Latency: %.2fms | CPU: %.2fms | Oversleep: %.2fms | GPU: %.2fms | Sleep: %.2fms | AlignErr: %s | Starve: %d/%d | Mode: %s",
                        fps,
                        smoothPcMs,
                        smoothGameMs,
                        smoothOversleepMs,
                        smoothRenderMs,
                        smoothSleepMs,
                        alignErrText("%+.2fms"),
                        starveCount,
                        framesSinceLastLog,
                        mode));

                if (lastSleepReturnTime > 0 && lastGpuEndTimeSystem > 0) {
                    long inputSampleRelNs = Math.max(0, lastInputSampleTime - lastSleepReturnTime);
                    long simStartRelNs = Math.max(0, lastSimulationStartTime - lastSleepReturnTime);
                    long simEndRelNs = Math.max(0, lastSimulationEndTime - lastSleepReturnTime);
                    long renderSubmitStartRelNs = Math.max(0, lastRenderSubmitStartTime - lastSleepReturnTime);
                    long renderSubmitEndRelNs = Math.max(0, lastRenderSubmitEndTime - lastSleepReturnTime);
                    long presentStartRelNs = Math.max(0, lastPresentStartTime - lastSleepReturnTime);
                    long presentEndRelNs = Math.max(0, lastPresentEndTime - lastSleepReturnTime);
                    long gpuStartRelNs = lastGpuStartTimeSystem - lastSleepReturnTime;
                    long gpuEndRelNs = lastGpuEndTimeSystem - lastSleepReturnTime;
                    long gpuWorkNs = Math.max(0, lastGpuEndTimeSystem - lastGpuStartTimeSystem);
                    long trueLogPcNs = Math.max(0, lastGpuEndTimeSystem - lastSleepReturnTime);

                    double inputSampleMsVal = inputSampleRelNs / 1_000_000.0;
                    double simStartMsVal = simStartRelNs / 1_000_000.0;
                    double simEndMsVal = simEndRelNs / 1_000_000.0;
                    double renderSubmitStartMsVal = renderSubmitStartRelNs / 1_000_000.0;
                    double renderSubmitEndMsVal = renderSubmitEndRelNs / 1_000_000.0;
                    double presentStartMsVal = presentStartRelNs / 1_000_000.0;
                    double presentEndMsVal = presentEndRelNs / 1_000_000.0;
                    double gpuStartMsVal = gpuStartRelNs / 1_000_000.0;
                    double gpuEndMsVal = gpuEndRelNs / 1_000_000.0;
                    double gpuWorkMsVal = gpuWorkNs / 1_000_000.0;
                    double logOversleepMsVal = lastOversleepNs / 1_000_000.0;
                    // 标记之间的空档。NVIDIA 只给七个标记命名, 空档不作专名, 故一律用 "A→B" 描述。
                    double gapInputSimMs = Math.max(0, simStartRelNs - inputSampleRelNs) / 1_000_000.0;
                    double gapSimSubmitMs = Math.max(0, renderSubmitStartRelNs - simEndRelNs) / 1_000_000.0;
                    double gapSubmitPresentMs = Math.max(0, presentStartRelNs - renderSubmitEndRelNs) / 1_000_000.0;
                    double logAlignErrMsVal = alignErrNs / 1_000_000.0;
                    double truePcMsVal = trueLogPcNs / 1_000_000.0;
                    String logAlignErrText = alignErrNs != Long.MIN_VALUE
                            ? String.format(Locale.ROOT, "%+.2fms", logAlignErrMsVal) : "n/a";

                    LOGGER.info(String.format(
                            Locale.ROOT,
                            "[Reflex Timestamps] Frame #%d | Sleep: %.2fms | InputSample: T+%.2fms | Simulation: T+%.2fms..T+%.2fms | RenderSubmit: T+%.2fms..T+%.2fms | Present: T+%.2fms..T+%.2fms | GPU: T+%.2fms..T+%.2fms (Work: %.2fms, Oversleep: %.2fms) | Gaps: Input→Sim %.2fms, Sim→Submit %.2fms, Submit→Present %.2fms | AlignErr: %s | True PC Latency: %.2fms | ClockOffset: %dns",
                            frameCount,
                            sleepMsVal,
                            inputSampleMsVal,
                            simStartMsVal,
                            simEndMsVal,
                            renderSubmitStartMsVal,
                            renderSubmitEndMsVal,
                            presentStartMsVal,
                            presentEndMsVal,
                            gpuStartMsVal,
                            gpuEndMsVal,
                            gpuWorkMsVal,
                            logOversleepMsVal,
                            gapInputSimMs,
                            gapSimSubmitMs,
                            gapSubmitPresentMs,
                            logAlignErrText,
                            truePcMsVal,
                            lastClockOffset));
                }
                // 诊断: 等待计算的内部量与全部估计量, 用于还原"本次为什么等 0ms 还是 4ms"
                LOGGER.info(ReflexClient.getScheduler().getInternalsString());
            }
            lastLogTimeNs = now;
            framesSinceLastLog = 0;
            starveCount = 0;
        }
    }

    public synchronized List<String> getMetricsLines() {
        if (!ModConfig.INSTANCE.isTimelineDiagram()) {
            return Collections.singletonList(getMetricsString());
        }

        List<String> list = new ArrayList<>();
        String status = ModConfig.INSTANCE.isReflexEnabled() ? "§aEnabled" : "§cDisabled";
        String pcColor = smoothPcMs < 20.0 ? "§a" : (smoothPcMs < 30.0 ? "§e" : "§c");
        // 对齐误差: |err| 越小 = Present 起始越贴近 GPU 变闲时刻; 符号有意义故不取绝对值显示
        String alignColor = !alignErrValid ? "§7"
                : (Math.abs(smoothAlignErrMs) <= 0.5 ? "§a" : (Math.abs(smoothAlignErrMs) <= 2.0 ? "§e" : "§c"));

        list.add(String.format(Locale.ROOT,
                "§6[Reflex Pipeline] %sPC: %.1fms §7(Sleep: §e%.1fms§7 | %sAlignErr: %s§7 | %s§7)",
                pcColor, smoothPcMs, smoothSleepMs, alignColor, alignErrText("%+.1fms"), status));

        // 行标签的补白按 MC 默认字体【实测 advance】算出, 不是按字符数猜:
        // 空格=4px, Last=22, Input=26, Sim=14, Submit=30, GPU=18, Present=40 (实测自 ascii.png)。
        // 统一补到 38px: 前五行的词宽与 38 之差都是 4 的整数倍, 全部精确命中;
        // 只有 Present=40 已经超出, 纯空格缩不回去, 残差 +2px。
        boolean ok = lastSleepReturnTime > 0 && lastRenderSubmitStartTime > 0
                && lastGpuEndTimeSystem > 0 && lastPresentEndTime > 0;
        if (!ok) {
            list.add("§7└─ (等待时间戳就绪)");
            return list;
        }
        double tInput = Math.max(0, lastInputSampleTime - lastSleepReturnTime) / 1e6;
        double tSimStart = Math.max(0, lastSimulationStartTime - lastSleepReturnTime) / 1e6;
        double tSimEnd = Math.max(0, lastSimulationEndTime - lastSleepReturnTime) / 1e6;
        double tRenderStart = Math.max(0, lastRenderSubmitStartTime - lastSleepReturnTime) / 1e6;
        double tRenderEnd = Math.max(0, lastRenderSubmitEndTime - lastSleepReturnTime) / 1e6;
        double tPresentStart = Math.max(0, lastPresentStartTime - lastSleepReturnTime) / 1e6;
        double tPresentEnd = Math.max(0, lastPresentEndTime - lastSleepReturnTime) / 1e6;
        double tGpuStart = (lastGpuStartTimeSystem - lastSleepReturnTime) / 1e6;
        double tGpuEnd = (lastGpuEndTimeSystem - lastSleepReturnTime) / 1e6;
        boolean hasPrev = prevGpuEndTimeSystem > 0 && lastGpuEndTimeSystem > prevGpuEndTimeSystem;
        double tPrevGpuEnd = hasPrev ? (prevGpuEndTimeSystem - lastSleepReturnTime) / 1e6 : Double.NaN;

        double lo = 0, hi = Math.max(tGpuEnd, tPresentEnd);
        if (hasPrev) {
            lo = Math.min(lo, tPrevGpuEnd);
            hi = Math.max(hi, tPrevGpuEnd);
        }
        if (hi - lo < 1.0) hi = lo + 1.0;

        final int W = 56;

        // Last 行: 单根亮白 | = 上一帧 GPU 完成时刻(= GPU 变闲时刻)。
        // 控制律把本帧 renderSubmitEnd 摆到这一时刻上, 所以对齐正确时 | 应紧贴在 Submit 条右端,
        // 两者间隔即 AlignErr(正 = 提交晚了, 负 = 提交早了)。
        // 尾部说明与其它行同格式: 点名这根 | 是什么、在本帧起点后多久、以及它就是 AlignErr 的零点。
        if (hasPrev) {
            list.add(String.format(Locale.ROOT, "§7├─ %s│ %s §7%s", "Last    ",
                    markedTrack(W, pos(tPrevGpuEnd, lo, hi, W)),
                    String.format(Locale.ROOT, "上帧GPU完成: T+%.2fms | AlignErr零点 %s",
                            tPrevGpuEnd, alignErrText("%+.2fms"))));
        }

        // 每行只画自己那个标记区间, 相邻行之间的暗轨就是两段标记之间的空档。
        // NVIDIA 不给这些空档命名, 因此这里也只用 "→某标记" 描述, 不发明术语。
        list.add(timelineRow("Input   ", "§a", lo, hi, W, 0.0, tInput,
                String.format(Locale.ROOT, "Input: T+%.2fms | →Sim: %.2fms",
                        tInput, Math.max(0, tSimStart - tInput))));
        list.add(timelineRow2("Sim      ", "§b", "§e", lo, hi, W, tSimStart, tSimEnd, tRenderStart,
                String.format(Locale.ROOT, "Simulation: %.2fms | →RenderSubmit: %.2fms",
                        Math.max(0, tSimEnd - tSimStart), Math.max(0, tRenderStart - tSimEnd))));
        list.add(timelineRow("Submit  ", "§3", lo, hi, W, tRenderStart, tRenderEnd,
                String.format(Locale.ROOT, "RenderSubmit: %.2fms | →Present: %.2fms",
                        Math.max(0, tRenderEnd - tRenderStart), Math.max(0, tPresentStart - tRenderEnd))));
        list.add(timelineRow("Present", "§6", lo, hi, W, tPresentStart, tPresentEnd,
                String.format(Locale.ROOT, "Present: %.2fms", Math.max(0, tPresentEnd - tPresentStart))));
        list.add(timelineRow("GPU     ", "§d", lo, hi, W, tGpuStart, tGpuEnd,
                String.format(Locale.ROOT, "GPU: %.2fms | Oversleep: %.2fms",
                        Math.max(0, tGpuEnd - tGpuStart), smoothOversleepMs)));
        return list;
    }

    /** 时间 → 字符列(按窗口 [lo,hi] 线性映射到 [0,W])。 */
    private static int pos(double t, double lo, double hi, int w) {
        if (hi <= lo) return 0;
        int p = (int) Math.round((t - lo) / (hi - lo) * w);
        return Math.max(0, Math.min(w, p));
    }

    /** AlignErr: 做过对齐就带符号显示, 没做过就 n/a。 */
    private String alignErrText(String fmt) {
        return alignErrValid ? String.format(Locale.ROOT, fmt, smoothAlignErrMs) : "n/a";
    }

    /** 暗轨 + 亮白 | 标记(独立上色, 保证在深色背景上可见)。 */
    private static String markedTrack(int w, int... marks) {
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        for (int m : marks) {
            m = Math.max(0, Math.min(w - 1, m));
            if (m < prev) continue;
            sb.append("§8").append(repeatChar('░', m - prev));
            sb.append("§f|");
            prev = m + 1;
        }
        sb.append("§8").append(repeatChar('░', w - prev));
        return sb.toString();
    }

    /**
     * 一行甘特: 长度为 W 的轨道, [segStart, segEnd] 区间用实心块, 其余用暗轨。
     * 区间外(轨道左侧/右侧)按真实空档留空 —— 开/关 mod 都反映真实时序。
     */
    private static String timelineBar(double lo, double hi, int w,
                                      double segStart, double segEnd, String color,
                                      double[] extraSeg, String extraColor) {
        int pa = pos(segStart, lo, hi, w);
        int pb = pos(segEnd, lo, hi, w);
        pb = Math.max(pb, pa);
        String track = repeatChar('░', w);
        String line = track.substring(0, pa) + repeatChar('█', pb - pa) + track.substring(pb);
        StringBuilder colored = new StringBuilder();
        colored.append("§8").append(line, 0, pa);
        colored.append(color).append(repeatChar('█', pb - pa));
        int tailFrom = pb;
        if (extraSeg != null && extraSeg.length == 2 && extraColor != null) {
            int q = Math.max(pb, pos(extraSeg[0], lo, hi, w));
            int pr = Math.max(q, pos(extraSeg[1], lo, hi, w));
            colored.append("§8").append(line, pb, Math.max(pb, q));
            colored.append(extraColor).append(repeatChar('▒', Math.max(0, pr - Math.max(pb, q))));
            tailFrom = Math.max(tailFrom, pr);
        }
        colored.append("§8").append(line, Math.min(w, tailFrom), w);
        return colored.toString();
    }

    /** 一行甘特: 长度为 W 的轨道, [segStart, segEnd] 区间用实心块, 其余用暗轨。 */
    private static String timelineRow(String label, String color, double lo, double hi,
                                      int w, double segStart, double segEnd, String extra) {
        String colored = timelineBar(lo, hi, w, segStart, segEnd, color, null, null);
        return String.format(Locale.ROOT, "§7├─ %s│ %s §7%s", label, colored, extra);
    }

    /** 两段式甘特: [a,b] 用 c1 实心块(本标记区间), [b,c] 用 c2 半块(到下一标记之间的空档)。 */
    private static String timelineRow2(String label, String c1, String c2, double lo, double hi,
                                       int w, double a, double b, double c, String extra) {
        String colored = timelineBar(lo, hi, w, a, b, c1, new double[]{b, c}, c2);
        return String.format(Locale.ROOT, "§7├─ %s│ %s §7%s", label, colored, extra);
    }

    private static String repeatChar(char ch, int count) {
        if (count <= 0) return "";
        char[] arr = new char[count];
        java.util.Arrays.fill(arr, ch);
        return new String(arr);
    }

    public synchronized String getMetricsString() {
        return String.format(
                Locale.ROOT,
                "[Reflex] PC: %.1fms | CPU: %.1fms | Oversleep: %.1fms | GPU: %.1fms | Sleep: %.1fms | AlignErr: %s (%s)",
                smoothPcMs,
                smoothGameMs,
                smoothOversleepMs,
                smoothRenderMs,
                smoothSleepMs,
                alignErrText("%+.2fms"),
                ModConfig.INSTANCE.isReflexEnabled() ? "Enabled" : "Disabled");
    }
}
