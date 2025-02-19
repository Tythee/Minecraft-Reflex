package com.example;

import com.example.config.ModConfig;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.example.Reflex.MOD_ID;

public class ReflexScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 指数平滑参数
    private final float alpha = 0.85f;
    private Long estimateCpuTime = null;// CPU 时间指数平滑

    // GPU 时间滑动窗口
    private final int gpuWindowSize = 60;
    private final Deque<Long> gpuTimesHistory = new ArrayDeque<>();
    private final float[] gpuWeights;

    // 渲染队列
    public Deque<GpuTimeCollector> gpuTimeCollectorDeque = new ArrayDeque<>();

    public ReflexScheduler() {
        this.gpuWeights = new float[gpuWindowSize];
        // 初始化权重（指数衰减，如 1.5^(n-i)）
        for (int i = 0; i < gpuWindowSize; i++) {
            gpuWeights[i] = (float) Math.pow(1.5, gpuWindowSize - i - 1);
        }
    }

    // 更新 CPU 时间（指数平滑）
    public void updateCpuTime(long cpuTimeNs) {
        if (estimateCpuTime == null) {
            estimateCpuTime = cpuTimeNs;
        } else {
            estimateCpuTime = (long) (alpha * cpuTimeNs + (1 - alpha) * estimateCpuTime);
        }
    }

    // 更新 GPU 时间（滑动窗口加权平均）
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

//    private int lastAction = 0;

    private Long calculateWaitTime() {
        // 更新gpuTimeCollectorDeque元素的endQuery
        // for (GpuTimeCollector gpuTimeCollector : gpuTimeCollectorDeque) {
        // gpuTimeCollector.endQueryCheck();
        // }

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
//            System.out.println("gpuTimeCollectorDeque.isEmpty");
//            System.out.println("Estimate Gpu Time: " + getEstimateGpuTime());
//            System.out.println("Estimate Cpu Time: " + estimateCpuTime);
//            lastAction = 0;
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

            waitTime -= ModConfig.INSTANCE.getReduceWaitTime();// 减少等待时间，防止睡过头
            if (waitTime > 0) {
//                if (lastAction == 0) {
//                    System.out.println("Estimate Gpu Time: " + getEstimateGpuTime());
//                    System.out.println("Deque.size: " + gpuTimeCollectorDeque.size());
//                    System.out.println("Estimate Cpu Time: " + estimateCpuTime);
//                    System.out.println("waitTime: " + waitTime);
//                }
//                lastAction = 1;
                return waitTime;
            } else {
//                System.out.println("Estimate Gpu Time: " + getEstimateGpuTime());
//                System.out.println("Deque.size: " + gpuTimeCollectorDeque.size());
//                System.out.println("Estimate Cpu Time: " + estimateCpuTime);
//                System.out.println("waitTime: " + waitTime);
//                lastAction = 0;
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

        // if (lastRenderQueueAction != RenderQueueAction.START_WAIT &&
        // lastRenderQueueAction != null) {
        // LOGGER.error("renderQueueAdd called must after renderQueueStartWait or null",
        // new IllegalStateException("renderQueueAdd called must after
        // renderQueueStartWait or null"));
        // throw new IllegalStateException("renderQueueAdd called must after
        // renderQueueStartWait or null");
        // }

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

        // if (lastRenderQueueAction != RenderQueueAction.ADD) {
        // LOGGER.error("renderQueueEndInsert called must after renderQueueAdd",
        // new IllegalStateException("renderQueueEndInsert called must after
        // renderQueueAdd"));
        // throw new IllegalStateException("renderQueueEndInsert called must after
        // renderQueueAdd");
        // }

        currentOperateGpuTimeCollector.endQueryInsert();

        lastRenderQueueAction = RenderQueueAction.END_INSERT;
    }
}

enum RenderQueueAction {
    ADD,
    END_INSERT
}