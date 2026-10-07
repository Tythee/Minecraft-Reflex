package io.tythee;

import static io.tythee.ReflexClient.LOGGER;

//? if gte_26_2 {
//? if gte_26_3 {
/*import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
*///?}
//? if !gte_26_3 {
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
//?}
import com.mojang.blaze3d.systems.RenderSystem;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.OptionalLong;

public class GpuTimeCollector {
    public long frameId = 0;
    public long cpuStartTime;
    public long renderBuildStartTime;
    public long renderBuildEndTime;
    public long renderSubmitEndTime;
    public long cpuEndTime;
    public long waitDurationNs;

    public Long startTimeSystem = null;
    public Long endTimeSystem = null;
    public long clockOffset = 0;
    public Long startTimeGpu = null;
    public Long endTimeGpu = null;

    public boolean startQueryInserted = false;
    public boolean endQueryInserted = false;
    public boolean isReady = false;

    private static GpuQueryPool queryPool = null;
    private static boolean loggedInit = false;
    private static MethodHandle getTimestampNowHandle = null;
    private static long cachedOffset26 = 0;
    private static long lastCalibrateNs26 = 0;
    private static int nextCollectorId = 0;

    private final int slotId;
    private final int startSlot;
    private final int endSlot;

    static {
        try {
            Method m = GpuDevice.class.getDeclaredMethod("getTimestampNow");
            m.setAccessible(true);
            getTimestampNowHandle = MethodHandles.lookup().unreflect(m);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void ensureInitialized() {
        if (queryPool == null) {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device != null) {
                queryPool = device.createTimestampQueryPool(64);
                if (!loggedInit) {
                    loggedInit = true;
                    DeviceInfo info = device.getDeviceInfo();
                    LOGGER.info("[Reflex] Initialized on MC {} | Backend: {} | TimestampPeriod: {}ns | TimerPool: OK",
                            ReflexClient.MINECRAFT,
                            info.backendName(),
                            info.timestampPeriod());
                }
            }
        }
    }

    public static long getGpuToSystemOffset26(GpuDevice device) {
        long now = System.nanoTime();
        if (now - lastCalibrateNs26 >= 1_000_000_000L || lastCalibrateNs26 == 0) {
            float period = device.getDeviceInfo().timestampPeriod();
            try {
                if (getTimestampNowHandle != null) {
                    long rawGpu = (long) getTimestampNowHandle.invoke(device);
                    long gpuNs = (period == 1.0f) ? rawGpu : Math.round(rawGpu * (double) period);
                    cachedOffset26 = System.nanoTime() - gpuNs;
                    lastCalibrateNs26 = now;
                    return cachedOffset26;
                }
            } catch (Throwable ignored) {
            }
            if (device.getDeviceInfo().backendName().contains("OpenGL")) {
                long[] t = new long[1];
                org.lwjgl.opengl.GL33C.glGetInteger64v(org.lwjgl.opengl.GL33C.GL_TIMESTAMP, t);
                long gpuNs = (period == 1.0f) ? t[0] : Math.round(t[0] * (double) period);
                cachedOffset26 = System.nanoTime() - gpuNs;
                lastCalibrateNs26 = now;
                return cachedOffset26;
            }
        }
        return cachedOffset26;
    }

    public GpuTimeCollector() {
        this.slotId = (nextCollectorId++) % 32;
        this.startSlot = this.slotId * 2;
        this.endSlot = this.slotId * 2 + 1;
    }

    public void startQueryInsert() {
        ensureInitialized();
        if (queryPool != null) {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device != null) {
                CommandEncoder encoder = device.createCommandEncoder();
                encoder.writeTimestamp(queryPool, startSlot);
                startQueryInserted = true;
            }
        }
    }

    public void endQueryInsert() {
        if (!startQueryInserted || queryPool == null) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device != null) {
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.writeTimestamp(queryPool, endSlot);
            endQueryInserted = true;
        }
    }

    public boolean checkQuery() {
        if (!startQueryInserted || !endQueryInserted || queryPool == null) {
            return false;
        }
        if (isReady) {
            return true;
        }
        OptionalLong startVal = queryPool.getValue(startSlot);
        OptionalLong endVal = queryPool.getValue(endSlot);
        if (startVal.isPresent() && endVal.isPresent()) {
            GpuDevice device = RenderSystem.getDevice();
            float period = device.getDeviceInfo().timestampPeriod();
            long rawStart = startVal.getAsLong();
            long rawEnd = endVal.getAsLong();

            long rawDuration = Math.max(0, rawEnd - rawStart);
            long durationNs = (period == 1.0f) ? rawDuration : Math.round(rawDuration * (double) period);
            startTimeGpu = (period == 1.0f) ? rawStart : Math.round(rawStart * (double) period);
            endTimeGpu = startTimeGpu + durationNs;

            long offset = getGpuToSystemOffset26(device);
            if (offset == 0) {
                offset = cpuStartTime - startTimeGpu;
            }
            this.clockOffset = offset;
            startTimeSystem = startTimeGpu + offset;
            endTimeSystem = endTimeGpu + offset;
            isReady = true;
            return true;
        }
        return false;
    }

    public void reset() {
        startTimeSystem = null;
        endTimeSystem = null;
        startTimeGpu = null;
        endTimeGpu = null;
        startQueryInserted = false;
        endQueryInserted = false;
        isReady = false;
        waitDurationNs = 0;
        frameId = 0;
        cpuStartTime = 0;
        cpuEndTime = 0;
        renderBuildStartTime = 0;
        renderBuildEndTime = 0;
        renderSubmitEndTime = 0;
        clockOffset = 0;
    }
}
//?} else {
/*import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;

import static com.mojang.blaze3d.opengl.GlConst.GL_TRUE;

public class GpuTimeCollector {
    public long frameId = 0;
    public long cpuStartTime;
    public long renderBuildStartTime;
    public long renderBuildEndTime;
    public long renderSubmitEndTime;
    public long cpuEndTime;
    public long waitDurationNs;

    public Long startTimeSystem = null;
    public Long endTimeSystem = null;
    public long clockOffset = 0;
    public Long startTimeGpu = null;
    public Long endTimeGpu = null;

    public Integer startTimeQuery = null;
    public Integer endTimeQuery = null;

    public boolean startQueryInserted = false;
    public boolean endQueryInserted = false;
    public boolean isReady = false;

    private static long cachedOffset = 0;
    private static long lastCalibrateNs = 0;

    public static long getGpuToSystemOffset() {
        long now = System.nanoTime();
        if (now - lastCalibrateNs >= 1_000_000_000L || lastCalibrateNs == 0) {
            long[] t = new long[1];
            GL33C.glGetInteger64v(GL33C.GL_TIMESTAMP, t);
            cachedOffset = System.nanoTime() - t[0];
            lastCalibrateNs = now;
        }
        return cachedOffset;
    }

    public GpuTimeCollector() {
    }

    public void startQueryInsert() {
        startTimeQuery = GL32C.glGenQueries();
        GL33C.glQueryCounter(startTimeQuery, GL33C.GL_TIMESTAMP);
        startQueryInserted = true;
    }

    public void endQueryInsert() {
        if (!startQueryInserted) {
            return;
        }
        endTimeQuery = GL32C.glGenQueries();
        GL33C.glQueryCounter(endTimeQuery, GL33C.GL_TIMESTAMP);
        endQueryInserted = true;
    }

    public boolean checkQuery() {
        if (!startQueryInserted || !endQueryInserted || startTimeQuery == null || endTimeQuery == null) {
            return false;
        }
        if (isReady) {
            return true;
        }
        if (GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            endTimeGpu = GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT);
            startTimeGpu = GL33C.glGetQueryObjecti64(startTimeQuery, GL33C.GL_QUERY_RESULT);

            GL32C.glDeleteQueries(startTimeQuery);
            GL32C.glDeleteQueries(endTimeQuery);
            startTimeQuery = null;
            endTimeQuery = null;

            long offset = getGpuToSystemOffset();
            this.clockOffset = offset;
            startTimeSystem = startTimeGpu + offset;
            endTimeSystem = endTimeGpu + offset;
            isReady = true;
            return true;
        }
        return false;
    }

    public void reset() {
        if (startTimeQuery != null) {
            GL32C.glDeleteQueries(startTimeQuery);
            startTimeQuery = null;
        }
        if (endTimeQuery != null) {
            GL32C.glDeleteQueries(endTimeQuery);
            endTimeQuery = null;
        }
        startTimeSystem = null;
        endTimeSystem = null;
        startTimeGpu = null;
        endTimeGpu = null;
        startQueryInserted = false;
        endQueryInserted = false;
        isReady = false;
        waitDurationNs = 0;
        frameId = 0;
        cpuStartTime = 0;
        cpuEndTime = 0;
        renderBuildStartTime = 0;
        renderBuildEndTime = 0;
        renderSubmitEndTime = 0;
        clockOffset = 0;
    }
}
*///?}
