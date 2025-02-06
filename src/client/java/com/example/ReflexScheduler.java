package com.example;

import com.example.config.ModConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ReflexScheduler {
    // 指数平滑参数
    private float alpha = 0.85f;
    private Long estimateCpuTime = null;// CPU 时间指数平滑

    // GPU 时间滑动窗口
    private int gpuWindowSize = 60;
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

    private Long calculateWaitTime() {
        // 更新gpuTimeCollectorDeque元素的endQuery
        for (GpuTimeCollector gpuTimeCollector : gpuTimeCollectorDeque) {
            gpuTimeCollector.endQueryCheck();
        }

        if (gpuTimeCollectorDeque.isEmpty()) {
            return null;
        } else {
            if (getEstimateGpuTime() == null || estimateCpuTime == null) {
                return null;
            }

            long waitTime;
            if (gpuTimeCollectorDeque.getLast().startTimeGpu == null) {
                waitTime = getEstimateGpuTime() * gpuTimeCollectorDeque.size() - estimateCpuTime;

                System.out.println("==================Estimate Gpu Time: " + getEstimateGpuTime());
                System.out.println("==================Estimate Cpu Time: " + estimateCpuTime);
                System.out.println("==================waitTime: " + waitTime);

            } else {
                waitTime = gpuTimeCollectorDeque.getLast().startTimeSystem
                        + getEstimateGpuTime() * gpuTimeCollectorDeque.size()
                        - estimateCpuTime - System.nanoTime();

                System.out.println("Estimate Gpu Time: " + getEstimateGpuTime());
                System.out.println("Estimate Cpu Time: " + estimateCpuTime);
                System.out.println("waitTime: " + waitTime);
            }

            waitTime -= 1000000;// 减少等待时间，防止睡过头
            if (waitTime > 0) {
                return waitTime;
            } else {
                return null;
            }
        }
    }

    Lock lock = new ReentrantLock();
    Condition condition = lock.newCondition();

    public void Wait() {
        lock.lock();
        try {
            while (true) {
                Long waitTime = calculateWaitTime();
                if (waitTime != null && ModConfig.INSTANCE.isReflexEnabled()) {
                    boolean signaled = condition.await(waitTime, TimeUnit.NANOSECONDS);
                } else {
                    break;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    private GpuTimeCollector lastGpuTimeCollector = null;

    public void renderQueueAdd() {
        GpuTimeCollector gpuTimeCollector = new GpuTimeCollector();
        gpuTimeCollector.setCallback(() -> {
            lock.lock();
            try {
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }, () -> {
            lock.lock();
            try {
                condition.signalAll();
            } finally {
                lock.unlock();
            }
            updateGpuTime(gpuTimeCollector.endTimeGpu - gpuTimeCollector.startTimeGpu);
            gpuTimeCollectorDeque.remove(gpuTimeCollector);
        });
        gpuTimeCollector.startQueryInsert();
        gpuTimeCollectorDeque.addFirst(gpuTimeCollector);
        lastGpuTimeCollector = gpuTimeCollector;
    }

    public void renderQueueEndInsert() {
        if (lastGpuTimeCollector == null) {
            throw new IllegalStateException("renderQueueEndInsert called before renderQueueAdd");
        }
        lastGpuTimeCollector.endQueryInsert();
    }

    public void renderQueueStartWait() {
        if (lastGpuTimeCollector == null) {
            throw new IllegalStateException("renderQueueStartWait called before renderQueueAdd");
        }
        lastGpuTimeCollector.startQueryCheck();
        lastGpuTimeCollector = null;
    }
}