package com.example;

import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mojang.blaze3d.platform.GlConst.GL_TRUE;
import static com.example.Reflex.MOD_ID;

public class GpuTimeCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Long startTimeSystem = null;
    public Long endTimeSystem = null;
    public Long startTimeGpu = null;
    public Long endTimeGpu = null;
    public Integer startTimeQuery = null;
    public Integer endTimeQuery = null;

    private Runnable startCallback = null;
    private Runnable endCallback = null;

    GpuTimeCollector(Runnable startCallback, Runnable endCallback) {
        this.startCallback = startCallback;
        this.endCallback = endCallback;
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
    }

    public void startQueryCheck() {
        startTimeGpu = GL33C.glGetQueryObjecti64(startTimeQuery, GL33C.GL_QUERY_RESULT);// 等待GPU开始渲染
        startTimeSystem = System.nanoTime();
        GL33C.glDeleteQueries(startTimeQuery);
        startTimeQuery = null;
        if (startCallback != null) {
            startCallback.run();
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

    public void endQueryCheck() {
        if (!endQueryInserted) {
            LOGGER.error("endQueryInsert() must be called before endQueryCheck()",
                    new IllegalStateException("endQueryInsert() must be called before endQueryCheck()"));
            throw new IllegalStateException("endQueryInsert() must be called before endQueryCheck()");
        }

        if (GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            endTimeGpu = GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT);
            GL32C.glDeleteQueries(endTimeQuery);
            endTimeQuery = null;

            if (startTimeGpu == null) {
                LOGGER.error("startTimeGpu is null", new IllegalStateException("startTimeGpu is null"));
                throw new IllegalStateException("startTimeGpu is null");
            }

            endTimeSystem = startTimeSystem + endTimeGpu - startTimeGpu;
            if (endCallback != null) {
                endCallback.run();
            }
        }
    }
}