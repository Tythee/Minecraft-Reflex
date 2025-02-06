package com.example;

public class CpuTimeCollector {
    public Long startTime = null;
    public Long endTime = null;

    public void startCollect() {
        startTime = System.nanoTime();
    }

    public void endCollect() {
        endTime = System.nanoTime();
    }

    public Long getCpuTime() {
        if (startTime == null || endTime == null) {
            return null;
        }
        return endTime - startTime;
    }

    public void reset() {
        startTime = null;
        endTime = null;
    }
}
