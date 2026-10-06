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
    private long adaptiveMarginNs = 0L; // default 0.00ms
    private int healthyFramesCount = 0;
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
        if (estimateFirstBatchFlushNs == null) {
            estimateFirstBatchFlushNs = flushNs;
        } else {
            estimateFirstBatchFlushNs = (long) (0.15 * flushNs + 0.85 * estimateFirstBatchFlushNs);
        }
    }

    public long getEffectiveFirstBatchFlushNs() {
        if (estimateFirstBatchFlushNs != null) {
            return estimateFirstBatchFlushNs;
        }
        if (estimateSubmissionTime != null && estimateSubmissionTime > 0) {
            return Math.min(Math.max(300_000L, (long) (0.25 * estimateSubmissionTime)), 1_500_000L);
        }
        return 800_000L; // default 0.80ms
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

    public long getEffectiveSafetyMarginNs() {
        if (ModConfig.INSTANCE.isAdaptiveMargin()) {
            return adaptiveMarginNs;
        }
        return 0L;
    }

    public void checkCompletedQueries() {
        Iterator<GpuTimeCollector> iterator = gpuTimeCollectorDeque.iterator();
        while (iterator.hasNext()) {
            GpuTimeCollector col = iterator.next();
            if (col.startQueryInserted && col.endQueryInserted) {
                if (col.checkQuery()) {
                    processCompletedFrame(col);
                    iterator.remove();
                    collectorPool.returnObject(col);
                } else {
                    break;
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
        // Approach 3 (Dynamic Baseline Ratio): 25% of submission duration, clamped [0.3ms, 1.5ms]
        long baselineFlushNs = Math.min(Math.max(300_000L, (long) (0.25 * subDuration)), 1_500_000L);
        if (subDuration > 0 && baselineFlushNs > subDuration) {
            baselineFlushNs = (long) (0.5 * subDuration);
        }

        // Approach 1 (Online Hardware Calibration on 0-Queue / Healthy Frame)
        // When GPU was not blocked by previous frame (queue == 0), its start timestamp marks when commands arrived!
        boolean isZeroQueue = col.startTimeSystem != null
                && lastFrameGpuEndTimeSystem != null
                && col.startTimeSystem >= (lastFrameGpuEndTimeSystem - 100_000L);

        if (isZeroQueue && col.startTimeSystem != null && col.renderBuildStartTime > 0
                && col.startTimeSystem >= col.renderBuildStartTime) {
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

        boolean isStarved = false;
        Long estGpu = getEstimateGpuTime();
        boolean isGpuBound = estGpu != null && estimateCpuTime != null && estGpu > estimateCpuTime;
        boolean didWait = col.waitDurationNs > 200_000L;

        if (lastFrameGpuEndTimeSystem != null && isGpuBound) {
            long gap = col.startTimeSystem - lastFrameGpuEndTimeSystem;
            // Starvation requires meaningful GPU idle gap (>1.0ms) on a frame where Reflex delayed
            if (didWait && gap > 1_000_000L) {
                isStarved = true;
                handleGpuStarvation(gap, col.waitDurationNs, pureCpuDuration);
            } else if (didWait && gap <= 1_000_000L) {
                handleHealthyFrame();
            }
        }
        lastFrameGpuEndTimeSystem = col.endTimeSystem;

        ReflexMetrics.getInstance().recordFrame(
                col,
                pureCpuDuration,
                simDuration,
                subDuration,
                flushDelay,
                gpuDuration,
                overlapNs,
                col.waitDurationNs,
                getEffectiveSafetyMarginNs(),
                isStarved
        );
    }

    private void handleGpuStarvation(long gapNs, long waitNs, long actualCpuNs) {
        if (!ModConfig.INSTANCE.isAdaptiveMargin()) {
            return;
        }
        double gapMs = gapNs / 1_000_000.0;
        double waitMs = waitNs / 1_000_000.0;
        double cpuMs = actualCpuNs / 1_000_000.0;
        double avgCpuMs = (estimateCpuTime != null ? estimateCpuTime : actualCpuNs) / 1_000_000.0;
        double prevMarginMs = adaptiveMarginNs / 1_000_000.0;

        // If CPU took noticeably longer than normal, it is a CPU spike, not over-delayed
        if (estimateCpuTime != null && (actualCpuNs - estimateCpuTime > 1_500_000L || actualCpuNs > 1.30 * estimateCpuTime)) {
            if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
                ReflexClient.LOGGER.info(String.format(
                        Locale.ROOT,
                        "[Reflex Adaptive] GPU starvation ignored due to CPU spike (cpu=%.2fms, avg=%.2fms). Margin preserved: %.2fms",
                        cpuMs, avgCpuMs, prevMarginMs));
            }
            healthyFramesCount = 0;
            return;
        }

        adaptiveMarginNs = Math.min(1_500_000L, adaptiveMarginNs + 100_000L); // +0.10ms, cap at 1.5ms
        double newMarginMs = adaptiveMarginNs / 1_000_000.0;
        healthyFramesCount = 0;

        if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
            ReflexClient.LOGGER.info(String.format(
                    Locale.ROOT,
                    "[Reflex Adaptive] GPU starvation detected (gap=%.2fms, wait=%.2fms, cpu=%.2fms). Cause: Over-delayed. Adjusting margin: +0.10ms -> %.2fms",
                    gapMs, waitMs, cpuMs, newMarginMs));
        }
    }

    private void handleHealthyFrame() {
        if (!ModConfig.INSTANCE.isAdaptiveMargin()) {
            return;
        }
        healthyFramesCount++;
        if (healthyFramesCount >= 60) {
            healthyFramesCount = 0;
            if (adaptiveMarginNs > 0L) { // floor at 0.00ms
                adaptiveMarginNs = Math.max(0L, adaptiveMarginNs - 10_000L); // decay by -0.010ms
                double newMarginMs = adaptiveMarginNs / 1_000_000.0;
                if (ModConfig.INSTANCE.isEnableDiagnosticLogging()) {
                    ReflexClient.LOGGER.info(String.format(
                            Locale.ROOT,
                            "[Reflex Adaptive] Pipeline healthy for 60 frames. Margin decayed: -0.010ms -> %.3fms",
                            newMarginMs));
                }
            }
        }
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
        long margin = getEffectiveSafetyMarginNs();

        // If CPU work alone is longer than or equal to GPU frame time, we are CPU-bound.
        // No queue buildup can happen in front of the GPU; never sleep.
        if (leadTime + margin >= estGpu) {
            return null;
        }

        long now = System.nanoTime();
        int inFlight = gpuTimeCollectorDeque.size();

        long targetGpuFinish;
        if (inFlight == 0) {
            // No uncompleted frames in flight. If GPU has finished all work and is idle or unknown, don't sleep.
            if (lastFrameGpuEndTimeSystem == null || lastFrameGpuEndTimeSystem <= now) {
                return null;
            }
            targetGpuFinish = lastFrameGpuEndTimeSystem;
        } else {
            GpuTimeCollector oldest = gpuTimeCollectorDeque.peekFirst();
            long baseTime;
            if (lastFrameGpuEndTimeSystem != null && oldest != null && oldest.cpuStartTime > 0) {
                baseTime = Math.max(lastFrameGpuEndTimeSystem, oldest.cpuStartTime);
            } else if (oldest != null && oldest.cpuStartTime > 0) {
                baseTime = oldest.cpuStartTime;
            } else if (lastFrameGpuEndTimeSystem != null) {
                baseTime = lastFrameGpuEndTimeSystem;
            } else {
                baseTime = now;
            }
            targetGpuFinish = baseTime + (long) inFlight * estGpu;
        }

        long targetPollTime = targetGpuFinish - leadTime - margin;
        long waitTime = targetPollTime - now;

        waitTime += ModConfig.INSTANCE.getManualWaitOffsetNs();
        waitTime -= ModConfig.INSTANCE.getReduceWaitTime();

        // Safety clamp: wait time must never exceed inFlight * estGpu + estGpu, capped at 3 frames
        long maxWait = Math.max(estGpu, (long) Math.min(3, inFlight + 1) * estGpu);
        if (waitTime > maxWait) {
            waitTime = maxWait;
        }

        if (waitTime > 50_000L) { // Only sleep if > 0.05ms
            return waitTime;
        }
        return null;
    }

    public long Wait() {
        checkCompletedQueries();
        if (!ModConfig.INSTANCE.isReflexEnabled()) {
            return 0L;
        }

        Long waitTime = calculateWaitTime();
        if (waitTime == null || waitTime <= 0) {
            return 0L;
        }

        long startWait = System.nanoTime();
        long targetTime = startWait + waitTime;
        // Leave 1.5ms for fine-grained spin-wait to avoid Windows thread scheduling oversleep
        long coarseSleepNs = waitTime - 1_500_000L;
        if (coarseSleepNs > 0) {
            LockSupport.parkNanos(coarseSleepNs);
        }
        while (System.nanoTime() < targetTime) {
            Thread.onSpinWait();
        }
        return System.nanoTime() - startWait;
    }

    public void startFrame(long cpuStartTime, long waitNs) {
        GpuTimeCollector collector = collectorPool.borrow();
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
