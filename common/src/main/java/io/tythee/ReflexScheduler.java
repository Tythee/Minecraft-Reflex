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
    private final long[] gpuTimeRingBuffer = new long[gpuWindowSize];
    private int ringBufferIndex = 0;
    private int validSamples = 0;

    public Deque<GpuTimeCollector> gpuTimeCollectorDeque = new ArrayDeque<>();
    
    ObjectPool.ObjectFactory<GpuTimeCollector> collectorFactory = () -> new GpuTimeCollector();
    ObjectPool.ObjectResetter<GpuTimeCollector> collectorResetter = collector -> {
        collector.reset();
    };
    private final ObjectPool<GpuTimeCollector> collectorPool = new ObjectPool<>(collectorFactory, collectorResetter);

    private final float weightBase = 1.5f;
    private final float[] gpuWeights;

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

    private static final double NS_TO_SECONDS = 1e-9;

    public void Wait() {
        while (true) {
            Long waitTime = calculateWaitTime();
            if (waitTime != null && ModConfig.INSTANCE.isReflexEnabled()) {
                GLFW.glfwWaitEventsTimeout(waitTime * NS_TO_SECONDS);
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
            collectorPool.returnObject(currentOperateGpuTimeCollector);
            currentOperateGpuTimeCollector = null;
            lastRenderQueueAction = null;
        }
    
        GpuTimeCollector gpuTimeCollector = collectorPool.borrow();
        gpuTimeCollector.setCallback(
                null, () -> {
                    updateGpuTime(gpuTimeCollector.endTimeSystem - gpuTimeCollector.startTimeSystem);
                    collectorPool.returnObject(gpuTimeCollector);
                });
        gpuTimeCollector.startQueryInsert();
        gpuTimeCollectorDeque.addFirst(gpuTimeCollector);
        currentOperateGpuTimeCollector = gpuTimeCollector;
        lastRenderQueueAction = RenderQueueAction.ADD;
    }

    public void renderQueueEndInsert() {
        if (lastRenderQueueAction != RenderQueueAction.ADD) {
            gpuTimeCollectorDeque.remove(currentOperateGpuTimeCollector);
            collectorPool.returnObject(currentOperateGpuTimeCollector);
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