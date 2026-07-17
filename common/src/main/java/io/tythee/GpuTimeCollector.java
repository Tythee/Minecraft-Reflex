package io.tythee;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.OptionalLong;

import static io.tythee.ReflexClient.LOGGER;

public class GpuTimeCollector {
    private static final int START_QUERY = 0;
    private static final int END_QUERY = 1;

    public Long startTimeSystem = null;
    private Long startTimeGpu = null;
    private Long endTimeGpu = null;
    private Long gpuTimeNs = null;

    private GpuQueryPool queryPool = null;
    private CommandEncoder queryEncoder = null;

    private Runnable startCallback = null;
    private Runnable endCallback = null;

    GpuTimeCollector() {
    }

    public void setCallback(Runnable startCallback, Runnable endCallback) {
        this.startCallback = startCallback;
        this.endCallback = endCallback;
    }

    public void startQueryInsert() {
        if (queryPool != null) {
            throw new IllegalStateException("GPU query has already been created");
        }

        GpuDevice device = RenderSystem.getDevice();
        queryPool = device.createTimestampQueryPool(2);
        queryEncoder = device.createCommandEncoder();
        queryEncoder.writeTimestamp(queryPool, START_QUERY);

        // OpenGL executes the timestamp write immediately, so it can also be used
        // as an approximation of the corresponding CPU time. Vulkan records the
        // timestamp into a command buffer and does not have that property.
        if ("OpenGL".equalsIgnoreCase(device.getDeviceInfo().backendName())) {
            startTimeSystem = System.nanoTime();
        }

        startQueryInserted = true;
    }

    public void startQueryCheck() {
        if (!startQueryInserted) {
            LOGGER.error("startQueryInsert() must be called before startQueryCheck()",
                    new IllegalStateException("startQueryInsert() must be called before startQueryCheck()"));
            throw new IllegalStateException("startQueryInsert() must be called before startQueryCheck()");
        }

        if (startTimeGpu == null) {
            OptionalLong result = queryPool.getValue(START_QUERY);
            if (result.isPresent()) {
                startTimeGpu = result.getAsLong();

                if (startCallback != null) {
                    startCallback.run();
                }
            }
        }
    }

    public boolean startQueryInserted = false;
    public boolean endQueryInserted = false;

    public void endQueryInsert() {
        if (!startQueryInserted) {
            LOGGER.error("startQueryInsert() must be called before endQueryInsert()",
                    new IllegalStateException("startQueryInsert() must be called before endQueryInsert()"));
            throw new IllegalStateException("startQueryInsert() must be called before endQueryInsert()");
        }

        queryEncoder.writeTimestamp(queryPool, END_QUERY);

        endQueryInserted = true;
    }

    public boolean endQueryCheck() {
        if (!endQueryInserted) {
            LOGGER.error("endQueryInsert() must be called before endQueryCheck()",
                    new IllegalStateException("endQueryInsert() must be called before endQueryCheck()"));
            throw new IllegalStateException("endQueryInsert() must be called before endQueryCheck()");
        }

        OptionalLong[] results = queryPool.getValues(0, 2);
        if (results[0].isPresent() && results[1].isPresent()) {
            startTimeGpu = results[0].getAsLong();
            endTimeGpu = results[1].getAsLong();

            float timestampPeriod = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
            gpuTimeNs = Math.max(0L, (long) ((endTimeGpu - startTimeGpu) * timestampPeriod));

            GpuQueryPool completedQueryPool = queryPool;
            queryPool = null;
            queryEncoder = null;
            completedQueryPool.close();

            if (endCallback != null) {
                endCallback.run();
            }

            return true;
        }
        return false;
    }

    public Long getGpuTime() {
        return gpuTimeNs;
    }

    public void reset() {
        if (queryPool != null) {
            queryPool.close();
            queryPool = null;
        }
        queryEncoder = null;
        startTimeSystem = null;
        startTimeGpu = null;
        endTimeGpu = null;
        gpuTimeNs = null;
        startQueryInserted = false;
        endQueryInserted = false;
    }
}
