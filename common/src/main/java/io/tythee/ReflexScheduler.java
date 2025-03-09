package io.tythee;

import io.tythee.config.ModConfig;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class ReflexScheduler {
    private final float alpha = 0.85f;
    private Long estimateCpuTime = null;

    private final int gpuWindowSize = 60;
    private final Deque<Long> gpuTimesHistory = new ArrayDeque<>();
    private final float[] gpuWeights;

    public Deque<GpuTimeCollector> gpuTimeCollectorDeque = new ArrayDeque<>();

    public ReflexScheduler() {
        this.gpuWeights = new float[gpuWindowSize];
        for (int i = 0; i < gpuWindowSize; i++) {
            gpuWeights[i] = (float) Math.pow(1.5, gpuWindowSize - i - 1);
        }
    }

    public void updateCpuTime(long cpuTimeNs) {
        if (estimateCpuTime == null) {
            estimateCpuTime = cpuTimeNs;
        } else {
            estimateCpuTime = (long) (alpha * cpuTimeNs + (1 - alpha) * estimateCpuTime);
        }
    }

    public void updateGpuTime(long gpuTimeNs) {
        if (gpuTimesHistory.size() >= gpuWindowSize) {
            gpuTimesHistory.removeLast();
        }
        gpuTimesHistory.addFirst(gpuTimeNs);
    }

    public Long getEstimateGpuTime() {
        if (gpuTimesHistory.isEmpty()) {
            return null;
        }
        float totalWeight = 0;
        long totalGpu = 0;
        int i = 0;
        for (Long gpu : gpuTimesHistory) {
            totalGpu += (long) (gpu * gpuWeights[i]);
            totalWeight += gpuWeights[i];
            i++;
        }
        long avgGpu = (long) (totalGpu / totalWeight);
        return avgGpu;
    }

    private Long calculateWaitTime() {

        Iterator<GpuTimeCollector> gpuTimeCollectorIterator = gpuTimeCollectorDeque.iterator();
        while (gpuTimeCollectorIterator.hasNext()) {
            GpuTimeCollector gpuTimeCollector = gpuTimeCollectorIterator.next();
            if (gpuTimeCollector.startQueryInserted && gpuTimeCollector.endQueryInserted) {
                gpuTimeCollector.startQueryCheck();
                if(gpuTimeCollector.endQueryCheck()){
                    gpuTimeCollectorIterator.remove();
                }
            } else {
                gpuTimeCollectorIterator.remove();
            }
        }

        if (gpuTimeCollectorDeque.isEmpty()) {
            return null;
        } else {
            if (getEstimateGpuTime() == null || estimateCpuTime == null) {
                return null;
            }

            long waitTime;
            if (gpuTimeCollectorDeque.getLast().startTimeSystem == null) {
                waitTime = getEstimateGpuTime() * gpuTimeCollectorDeque.size() - estimateCpuTime;
            } else {
                waitTime = gpuTimeCollectorDeque.getLast().startTimeSystem
                        + getEstimateGpuTime() * gpuTimeCollectorDeque.size()
                        - estimateCpuTime - System.nanoTime();
            }

            waitTime -= ModConfig.INSTANCE.getReduceWaitTime();
            if (waitTime > 0) {
                return waitTime;
            } else {
                return null;
            }
        }
    }

    public void Wait() {
        while (true) {
            Long waitTime = calculateWaitTime();
            if (waitTime != null && ModConfig.INSTANCE.isReflexEnabled()) {
                GLFW.glfwWaitEventsTimeout(waitTime / 1e9);
            } else {
                break;
            }
        }
    }

    private GpuTimeCollector currentOperateGpuTimeCollector = null;
    private RenderQueueAction lastRenderQueueAction = null;

    public void renderQueueAdd() {
        if (lastRenderQueueAction != RenderQueueAction.END_INSERT && lastRenderQueueAction != null) {
            gpuTimeCollectorDeque.remove(currentOperateGpuTimeCollector);
            currentOperateGpuTimeCollector = null;
            lastRenderQueueAction = null;
        }

        GpuTimeCollector gpuTimeCollector = new GpuTimeCollector();
        gpuTimeCollector.setCallback(
                null, () -> {
                    updateGpuTime(gpuTimeCollector.endTimeSystem - gpuTimeCollector.startTimeSystem);
                });

        gpuTimeCollector.startQueryInsert();

        gpuTimeCollectorDeque.addFirst(gpuTimeCollector);

        currentOperateGpuTimeCollector = gpuTimeCollector;
        lastRenderQueueAction = RenderQueueAction.ADD;
    }

    public void renderQueueEndInsert() {
        if (lastRenderQueueAction != RenderQueueAction.ADD) {
            gpuTimeCollectorDeque.remove(currentOperateGpuTimeCollector);
            currentOperateGpuTimeCollector = null;
            lastRenderQueueAction = null;
            return;
        }

        currentOperateGpuTimeCollector.endQueryInsert();

        lastRenderQueueAction = RenderQueueAction.END_INSERT;
    }
}

enum RenderQueueAction {
    ADD,
    END_INSERT
}