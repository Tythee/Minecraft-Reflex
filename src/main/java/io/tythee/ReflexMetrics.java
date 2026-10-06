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

    private long gameLatencyNs = 0;
    private long simLatencyNs = 0;
    private long subLatencyNs = 0;
    private long flushLatencyNs = 0;
    private long queueLatencyNs = 0;
    private long renderLatencyNs = 0;
    private long overlapNs = 0;
    private long waitNs = 0;
    private long safetyMarginNs = 0;
    private long pcLatencyNs = 0;

    private double smoothGameMs = 0;
    private double smoothSimMs = 0;
    private double smoothSubMs = 0;
    private double smoothFlushMs = 0;
    private double smoothQueueMs = 0;
    private double smoothRenderMs = 0;
    private double smoothOverlapMs = 0;
    private double smoothWaitMs = 0;
    private double smoothPcMs = 0;
    private double smoothMarginMs = 0;

    private int frameCount = 0;
    private int starveCount = 0;
    private long lastLogTimeNs = System.nanoTime();
    private int framesSinceLastLog = 0;

    private long lastCpuStartTime = 0;
    private long lastRenderBuildStartTime = 0;
    private long lastRenderBuildEndTime = 0;
    private long lastRenderSubmitEndTime = 0;
    private long lastCpuEndTime = 0;
    private long lastGpuStartTimeSystem = 0;
    private long lastGpuEndTimeSystem = 0;
    private long lastClockOffset = 0;
    private long lastWaitNs = 0;

    private static final float ALPHA = 0.15f;

    public synchronized void recordFrame(
            GpuTimeCollector col,
            long gameNs,
            long simNs,
            long subNs,
            long flushNs,
            long renderNs,
            long overlapNs,
            long waitNs,
            long marginNs,
            boolean starved) {

        long truePcNs = (col != null && col.endTimeSystem != null && col.endTimeSystem > 0 && col.cpuStartTime > 0)
                ? Math.max(0, col.endTimeSystem - col.cpuStartTime)
                : Math.max(0, gameNs + renderNs - overlapNs);

        long queueNs = (col != null && col.startTimeSystem != null && col.renderBuildEndTime > 0 && col.startTimeSystem > col.renderBuildEndTime)
                ? (col.startTimeSystem - col.renderBuildEndTime)
                : 0L;

        if (col != null) {
            this.lastCpuStartTime = col.cpuStartTime;
            this.lastRenderBuildStartTime = (col.renderBuildStartTime > 0) ? col.renderBuildStartTime : col.cpuStartTime;
            this.lastRenderBuildEndTime = (col.renderBuildEndTime > 0) ? col.renderBuildEndTime : col.cpuEndTime;
            this.lastRenderSubmitEndTime = (col.renderSubmitEndTime > 0) ? col.renderSubmitEndTime : col.cpuEndTime;
            this.lastCpuEndTime = col.cpuEndTime;
            this.lastGpuStartTimeSystem = (col.startTimeSystem != null) ? col.startTimeSystem : 0;
            this.lastGpuEndTimeSystem = (col.endTimeSystem != null) ? col.endTimeSystem : 0;
            this.lastClockOffset = col.clockOffset;
            this.lastWaitNs = col.waitDurationNs;
        }

        this.gameLatencyNs = gameNs;
        this.simLatencyNs = simNs;
        this.subLatencyNs = subNs;
        this.flushLatencyNs = flushNs;
        this.queueLatencyNs = queueNs;
        this.renderLatencyNs = renderNs;
        this.overlapNs = overlapNs;
        this.waitNs = waitNs;
        this.safetyMarginNs = marginNs;
        this.pcLatencyNs = truePcNs;

        double gameMs = gameNs / 1_000_000.0;
        double simMsVal = simNs / 1_000_000.0;
        double subMsVal = subNs / 1_000_000.0;
        double flushMsVal = flushNs / 1_000_000.0;
        double queueMsVal = queueNs / 1_000_000.0;
        double renderMs = renderNs / 1_000_000.0;
        double overlapMsVal = overlapNs / 1_000_000.0;
        double waitMsVal = waitNs / 1_000_000.0;
        double marginMsVal = marginNs / 1_000_000.0;
        double pcMs = this.pcLatencyNs / 1_000_000.0;

        if (frameCount == 0) {
            smoothGameMs = gameMs;
            smoothSimMs = simMsVal;
            smoothSubMs = subMsVal;
            smoothFlushMs = flushMsVal;
            smoothQueueMs = queueMsVal;
            smoothRenderMs = renderMs;
            smoothOverlapMs = overlapMsVal;
            smoothWaitMs = waitMsVal;
            smoothMarginMs = marginMsVal;
            smoothPcMs = pcMs;
        } else {
            smoothGameMs = ALPHA * gameMs + (1 - ALPHA) * smoothGameMs;
            smoothSimMs = ALPHA * simMsVal + (1 - ALPHA) * smoothSimMs;
            smoothSubMs = ALPHA * subMsVal + (1 - ALPHA) * smoothSubMs;
            smoothFlushMs = ALPHA * flushMsVal + (1 - ALPHA) * smoothFlushMs;
            smoothQueueMs = ALPHA * queueMsVal + (1 - ALPHA) * smoothQueueMs;
            smoothRenderMs = ALPHA * renderMs + (1 - ALPHA) * smoothRenderMs;
            smoothOverlapMs = ALPHA * overlapMsVal + (1 - ALPHA) * smoothOverlapMs;
            smoothWaitMs = ALPHA * waitMsVal + (1 - ALPHA) * smoothWaitMs;
            smoothMarginMs = ALPHA * marginMsVal + (1 - ALPHA) * smoothMarginMs;
            smoothPcMs = ALPHA * pcMs + (1 - ALPHA) * smoothPcMs;
        }

        frameCount++;
        framesSinceLastLog++;
        if (starved) {
            starveCount++;
        }

        long now = System.nanoTime();
        if (now - lastLogTimeNs >= 1_000_000_000L) {
            if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
                double elapsedSec = (now - lastLogTimeNs) / 1_000_000_000.0;
                double fps = framesSinceLastLog / elapsedSec;
                String mode = (smoothRenderMs >= smoothGameMs) ? "GPU-Bound" : "CPU-Bound";
                LOGGER.info(String.format(
                        Locale.ROOT,
                        "[Reflex Summary] FPS: %.1f | PC Latency: %.2fms | Game(CPU): %.2fms | Queue: +%.2fms | Render(GPU): %.2fms | Overlap: %.2fms | Wait: %.2fms | SafetyMargin: %.2fms | Starve: %d/%d | Mode: %s",
                        fps,
                        smoothPcMs,
                        smoothGameMs,
                        smoothQueueMs,
                        smoothRenderMs,
                        smoothOverlapMs,
                        smoothWaitMs,
                        smoothMarginMs,
                        starveCount,
                        framesSinceLastLog,
                        mode));

                if (lastCpuStartTime > 0 && lastGpuEndTimeSystem > 0) {
                    long renderBuildStartRelNs = Math.max(0, lastRenderBuildStartTime - lastCpuStartTime);
                    long renderBuildRelNs = Math.max(0, lastRenderBuildEndTime - lastCpuStartTime);
                    long submitRelNs = Math.max(0, lastRenderSubmitEndTime - lastCpuStartTime);
                    long presentRelNs = Math.max(0, lastCpuEndTime - lastCpuStartTime);
                    long fenceStallNs = Math.max(0, lastRenderSubmitEndTime - lastRenderBuildEndTime);
                    long gpuStartRelNs = lastGpuStartTimeSystem - lastCpuStartTime;
                    long gpuEndRelNs = lastGpuEndTimeSystem - lastCpuStartTime;
                    long logQueueNs = lastGpuStartTimeSystem - lastRenderBuildEndTime;
                    long gpuWorkNs = Math.max(0, lastGpuEndTimeSystem - lastGpuStartTimeSystem);
                    long trueLogPcNs = Math.max(0, lastGpuEndTimeSystem - lastCpuStartTime);

                    double waitSampleMs = lastWaitNs / 1_000_000.0;
                    double renderBuildStartMsVal = renderBuildStartRelNs / 1_000_000.0;
                    double renderBuildMsVal = renderBuildRelNs / 1_000_000.0;
                    double submitMsVal = submitRelNs / 1_000_000.0;
                    double fenceStallMsVal = fenceStallNs / 1_000_000.0;
                    double presentMsVal = presentRelNs / 1_000_000.0;
                    double gpuStartMsVal = gpuStartRelNs / 1_000_000.0;
                    double gpuEndMsVal = gpuEndRelNs / 1_000_000.0;
                    double logQueueMsVal = logQueueNs / 1_000_000.0;
                    double gpuWorkMsVal = gpuWorkNs / 1_000_000.0;
                    double truePcMsVal = trueLogPcNs / 1_000_000.0;

                    String queueStatus = (logQueueMsVal >= 0)
                            ? String.format(Locale.ROOT, "Queue: +%.2fms", logQueueMsVal)
                            : String.format(Locale.ROOT, "Overlap: %.2fms", -logQueueMsVal);

                    LOGGER.info(String.format(
                            Locale.ROOT,
                            "[Reflex Timestamps] Frame #%d | Wait: %.2fms | Input: T+0.00ms | RenderBuild: T+%.2fms..T+%.2fms | SubmitEnd: T+%.2fms (FenceStall: %.2fms) | PresentEnd: T+%.2fms | GPU: T+%.2fms..T+%.2fms (Work: %.2fms, %s) | True PC Latency: %.2fms | ClockOffset: %dns",
                            frameCount,
                            waitSampleMs,
                            renderBuildStartMsVal,
                            renderBuildMsVal,
                            submitMsVal,
                            fenceStallMsVal,
                            presentMsVal,
                            gpuStartMsVal,
                            gpuEndMsVal,
                            gpuWorkMsVal,
                            queueStatus,
                            truePcMsVal,
                            lastClockOffset));
                }
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
        String status = ModConfig.INSTANCE.isReflexEnabled()
                ? (ModConfig.INSTANCE.isAdaptiveMargin() ? "§aAdaptive" : "§eFixed")
                : "§cDisabled";

        String pcColor = smoothPcMs < 20.0 ? "§a" : (smoothPcMs < 30.0 ? "§e" : "§c");
        String marginStr = ModConfig.INSTANCE.isReflexEnabled()
                ? String.format(Locale.ROOT, " §7| Margin: §f%.2fms", smoothMarginMs)
                : "";

        list.add(String.format(Locale.ROOT,
                "§6[Reflex Pipeline] %sPC: %.1fms §7(Wait: §e%.1fms%s §7| %s§7)",
                pcColor, smoothPcMs, smoothWaitMs, marginStr, status));

        double simMs = Math.max(0.1, smoothSimMs);
        double subMs = Math.max(0.1, smoothSubMs);
        double flushMs = Math.min(subMs, Math.max(0.1, smoothFlushMs));
        double restSubMs = Math.max(0.0, subMs - flushMs);
        double queueMs = Math.max(0.0, smoothQueueMs);
        double renderMs = Math.max(0.5, smoothRenderMs);

        double cPerMs = smoothPcMs <= 15.0 ? 2.0 : 1.0;
        int simChars = Math.max(1, (int) Math.round(simMs * cPerMs));
        int flushChars = Math.max(1, (int) Math.round(flushMs * cPerMs));
        int restChars = Math.max(1, (int) Math.round(restSubMs * cPerMs));
        int queueChars = (int) Math.round(queueMs * cPerMs);
        int renderChars = Math.max(2, (int) Math.round(renderMs * cPerMs));

        // Line 1: Simulation (starts at left: T=0, ends at T=simMs)
        list.add(String.format(Locale.ROOT,
                "§7├─ §bSim  │ §b[%s] §7%.1fms",
                repeatChar('█', simChars), simMs));

        // Line 2: Render Submission (left edge starts at T=simMs, shows Flush and Rest)
        String subIndent = repeatChar(' ', simChars + 2);
        list.add(String.format(Locale.ROOT,
                "§7├─ §9Sub  │ %s§3[%s]§9[%s] §7(§3Flush: %.1fms §9Rest: %.1fms§7)",
                subIndent, repeatChar('█', flushChars), repeatChar('█', restChars), flushMs, restSubMs));

        // Line 3: GPU (starts after Sim + Flush + Queue)
        int gpuLeadChars = simChars + flushChars + 2;
        String gpuIndent = repeatChar(' ', gpuLeadChars);
        String qBar = queueChars > 0 ? String.format("§c[%s]", repeatChar('░', queueChars)) : "";
        String rBar = String.format("§d[%s]", repeatChar('█', renderChars));

        if (queueMs >= 0.1) {
            list.add(String.format(Locale.ROOT,
                    "§7└─ §dGPU  │ %s%s%s §7(§cQueue: +%.1fms §dRender: %.1fms§7)",
                    gpuIndent, qBar, rBar, queueMs, renderMs));
        } else {
            list.add(String.format(Locale.ROOT,
                    "§7└─ §dGPU  │ %s%s §7(§aZero Q §dRender: %.1fms §aOverlap: %.1fms§7)",
                    gpuIndent, rBar, renderMs, smoothOverlapMs));
        }

        return list;
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
                "[Reflex] PC: %.1fms | Game: %.1fms | Queue: +%.1fms | Render: %.1fms | Overlap: %.1fms | Wait: %.1fms | Margin: %.2fms (%s)",
                smoothPcMs,
                smoothGameMs,
                smoothQueueMs,
                smoothRenderMs,
                smoothOverlapMs,
                smoothWaitMs,
                smoothMarginMs,
                ModConfig.INSTANCE.isReflexEnabled()
                        ? (ModConfig.INSTANCE.isAdaptiveMargin() ? "Adaptive" : "Fixed")
                        : "Disabled");
    }

    public synchronized double getSmoothMarginMs() {
        return smoothMarginMs;
    }

    public synchronized double getSmoothPcMs() {
        return smoothPcMs;
    }

    public synchronized double getSmoothGameMs() {
        return smoothGameMs;
    }

    public synchronized double getSmoothQueueMs() {
        return smoothQueueMs;
    }

    public synchronized double getSmoothRenderMs() {
        return smoothRenderMs;
    }

    public synchronized double getSmoothOverlapMs() {
        return smoothOverlapMs;
    }

    public synchronized double getSmoothWaitMs() {
        return smoothWaitMs;
    }
}
