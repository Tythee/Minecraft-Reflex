package io.tythee;

import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;

import static com.mojang.blaze3d.platform.GlConst.GL_TRUE;
import static io.tythee.ReflexClient.LOGGER;

public class GpuTimeCollector {
    public Long startTimeSystem = null;
    public Long endTimeSystem = null;
    public Long startTimeGpu = null;
    public Long endTimeGpu = null;
    public Integer startTimeQuery = null;
    public Integer endTimeQuery = null;

    private Runnable startCallback = null;
    private Runnable endCallback = null;

    long gpuToSystem(long gpu) {
        long[] t = new long[1];
        GL33C.glGetInteger64v(GL33C.GL_TIMESTAMP, t);
        long system = System.nanoTime();
        long gpuToSystemOffset = system - t[0];
        return gpu + gpuToSystemOffset;
    }

    GpuTimeCollector() {
    }

    public void setCallback(Runnable startCallback, Runnable endCallback) {
        this.startCallback = startCallback;
        this.endCallback = endCallback;
    }

    public void startQueryInsert() {
        startTimeQuery = GL32C.glGenQueries();
        GL33C.glQueryCounter(startTimeQuery, GL33C.GL_TIMESTAMP);
        startQueryInserted = true;
        if (startTimeQuery == null) {
            throw new RuntimeException("Could not find query insertion time");
        }
    }

    public void startQueryCheck() {
        if (!startQueryInserted) {
            LOGGER.error("startQueryInsert() must be called before startQueryCheck()",
                    new IllegalStateException("startQueryInsert() must be called before startQueryCheck()"));
            throw new IllegalStateException("startQueryInsert() must be called before startQueryCheck()");
        }

        if (startTimeGpu == null) {
            if (GL33C.glGetQueryObjecti64(startTimeQuery, GL33C.GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
                startTimeGpu = GL33C.glGetQueryObjecti64(startTimeQuery, GL33C.GL_QUERY_RESULT);
                GL32C.glDeleteQueries(startTimeQuery);
                startTimeQuery = null;

                startTimeSystem = gpuToSystem(startTimeGpu);
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

        endTimeQuery = GL32C.glGenQueries();
        GL33C.glQueryCounter(endTimeQuery, GL33C.GL_TIMESTAMP);

        endQueryInserted = true;
    }

    public boolean endQueryCheck() {
        if (!endQueryInserted) {
            LOGGER.error("endQueryInsert() must be called before endQueryCheck()",
                    new IllegalStateException("endQueryInsert() must be called before endQueryCheck()"));
            throw new IllegalStateException("endQueryInsert() must be called before endQueryCheck()");
        }

        if (GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            endTimeGpu = GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT);
            GL32C.glDeleteQueries(endTimeQuery);
            endTimeQuery = null;

            startQueryCheck();
            if (startTimeGpu == null) {
                LOGGER.error("startTimeGpu is null", new IllegalStateException("startTimeGpu is null"));
                throw new IllegalStateException("startTimeGpu is null");
            }

            endTimeSystem = gpuToSystem(endTimeGpu);
            if (endCallback != null) {
                endCallback.run();
            }

            return true;
        }
        return false;
    }
}