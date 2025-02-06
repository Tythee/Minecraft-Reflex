package com.example;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;

import static com.mojang.blaze3d.platform.GlConst.GL_TRUE;

public class GpuTimeCollector {
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

    private Long startFence = null;

    public void startQueryInsert() {
        startTimeQuery = GL32C.glGenQueries();
        GL33C.glQueryCounter(startTimeQuery, GL33C.GL_TIMESTAMP);
        startFence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public void startQueryCheck() {
        GL32C.glClientWaitSync(startFence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, GL32C.GL_TIMEOUT_IGNORED);
        startTimeSystem = System.nanoTime();
        GL32C.glDeleteSync(startFence);
        startFence = null;

        if (GL33C.glGetQueryObjecti64(startTimeQuery, GL15C.GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            startTimeGpu = GL33C.glGetQueryObjecti64(startTimeQuery, GL33C.GL_QUERY_RESULT);
            GL33C.glDeleteQueries(startTimeQuery);
            startTimeQuery = null;
            if (startCallback != null) {
                startCallback.run();
            }
        }
    }

    public void endQueryInsert() {
        endTimeQuery = GL32C.glGenQueries();
        GL33C.glQueryCounter(endTimeQuery, GL33C.GL_TIMESTAMP);
    }

    public void endQueryCheck() {
        if (GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT_AVAILABLE) == GL_TRUE) {
            endTimeGpu = GL33C.glGetQueryObjecti64(endTimeQuery, GL33C.GL_QUERY_RESULT);
            GL32C.glDeleteQueries(endTimeQuery);
            endTimeQuery = null;
            endTimeSystem = startTimeSystem + endTimeGpu - startTimeGpu;
            if (endCallback != null) {
                endCallback.run();
            }
        }
    }
}
