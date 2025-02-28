package io.tythee;

public class CpuTimeCollector {

    public Long startTime = null;
    public Long endTime = null;

    public void startCollect() {
        startTime = System.nanoTime();
        lastAction = CpuTimeCollectorAction.START_COLLECT;
    }

    public void endCollect() {
        endTime = System.nanoTime();
        lastAction = CpuTimeCollectorAction.END_COLLECT;
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

    public CpuTimeCollectorAction lastAction = null;
}

enum CpuTimeCollectorAction {
    START_COLLECT,
    END_COLLECT
}
