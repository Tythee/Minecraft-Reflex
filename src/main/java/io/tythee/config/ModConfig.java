package io.tythee.config;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ModConfig {
    public static ModConfig INSTANCE = load();

    /** config/reflex.json 的上次观测修改时间: 运行时热重载用 (见 reloadIfChanged)。 */
    private static long lastSeenMtime = currentMtime();
    private static long reloadCount = 0L;

    private static long currentMtime() {
        try {
            File f = new File("config/reflex.json");
            return f.exists() ? f.lastModified() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 运行时热重载: config/reflex.json 的修改时间变了就重新读取并整体替换 INSTANCE。
     *
     * 由 ReflexMetrics 在每秒边界调用(1Hz), 因此不落在逐帧热路径上。
     * 用途: 让"同一局游戏内切换 mod 开关 / 改配置"可被外部脚本驱动, 也不必让用户重启游戏。
     * 注意: 控制器状态(offset/bias)在切换时**有意保留** —— 这样才观测得到重新收敛的过程。
     */
    public static boolean reloadIfChanged() {
        long m = currentMtime();
        if (m == lastSeenMtime) {
            return false;
        }
        lastSeenMtime = m;
        ModConfig fresh = load();
        INSTANCE = fresh;
        reloadCount++;
        try {
            io.tythee.ReflexClient.LOGGER.info(
                    "[Reflex Config] hot-reloaded (#{}) from config/reflex.json: {}",
                    reloadCount, fresh.toLogString());
        } catch (Throwable ignored) {
        }
        return true;
    }

    private boolean reflexEnabled = true;
    private boolean showLatencyMetrics = true;
    private boolean timelineDiagram = true;
    private boolean enableDiagnosticLogging = isDevEnvironment();
    private double manualSleepOffsetMs = 0.0;

    public boolean isReflexEnabled() {
        return reflexEnabled;
    }

    public void setReflexEnabled(boolean enabled) {
        this.reflexEnabled = enabled;
    }

    public boolean isTimelineDiagram() {
        return timelineDiagram;
    }

    public void setTimelineDiagram(boolean timelineDiagram) {
        this.timelineDiagram = timelineDiagram;
    }

    public boolean isShowLatencyMetrics() {
        return showLatencyMetrics;
    }

    public void setShowLatencyMetrics(boolean showLatencyMetrics) {
        this.showLatencyMetrics = showLatencyMetrics;
    }

    public boolean isEnableDiagnosticLogging() {
        return enableDiagnosticLogging;
    }

    public void setEnableDiagnosticLogging(boolean enableDiagnosticLogging) {
        this.enableDiagnosticLogging = enableDiagnosticLogging;
    }

    public double getManualSleepOffsetMs() {
        return manualSleepOffsetMs;
    }

    public void setManualSleepOffsetMs(double manualSleepOffsetMs) {
        this.manualSleepOffsetMs = manualSleepOffsetMs;
    }

    public long getManualSleepOffsetNs() {
        return (long) (manualSleepOffsetMs * 1_000_000.0);
    }

    public void setManualSleepOffsetNs(long manualSleepOffsetNs) {
        this.manualSleepOffsetMs = manualSleepOffsetNs / 1_000_000.0;
    }

    public static ModConfig load() {
        File configFile = new File("config/reflex.json");
        if (configFile.exists()) {
            try {
                Gson gson = new Gson();
                String json = new String(Files.readAllBytes(configFile.toPath()));
                ModConfig config = gson.fromJson(json, ModConfig.class);
                if (config != null) {
                    // 键名迁移: 历史顺序为 manualWaitOffsetNs(纳秒) → manualWaitOffsetMs(毫秒)
                    // → manualSleepOffsetMs(随控制面词汇统一)。Gson 按字段名读写, 若不回退读旧键,
                    // 老配置里的偏移量会被静默当作 0, 表现为"升级后手动偏移丢失"。
                    if (config.manualSleepOffsetMs == 0.0) {
                        try {
                            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                            if (!obj.has("manualSleepOffsetMs")) {
                                if (obj.has("manualWaitOffsetMs")) {
                                    config.manualSleepOffsetMs = obj.get("manualWaitOffsetMs").getAsDouble();
                                } else if (obj.has("manualWaitOffsetNs")) {
                                    config.manualSleepOffsetMs = obj.get("manualWaitOffsetNs").getAsLong() / 1_000_000.0;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return config;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return new ModConfig();
    }

    /** 全部配置项的一行摘要: 启动时与每次改动后写入日志, 便于事后确认"当时是什么设置"。 */
    public String toLogString() {
        return String.format(Locale.ROOT,
                "[Reflex Config] reflexEnabled=%s | manualSleepOffsetMs=%.3f | timelineDiagram=%s | showLatencyMetrics=%s | enableDiagnosticLogging=%s",
                reflexEnabled, manualSleepOffsetMs,
                timelineDiagram, showLatencyMetrics, enableDiagnosticLogging);
    }

    /** 精简开关摘要: 只含影响调度行为的项, 随逐秒诊断日志输出。 */
    public String toShortLogString() {
        return String.format(Locale.ROOT,
                "reflex:%s manualOffset:%+.3fms",
                reflexEnabled ? "on" : "off",
                manualSleepOffsetMs);
    }

    public static boolean isDevEnvironment() {        try {
            if (net.minecraft.SharedConstants.IS_RUNNING_IN_IDE) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> fl = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst = fl.getMethod("getInstance").invoke(null);
            if ((boolean) fl.getMethod("isDevelopmentEnvironment").invoke(inst)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> fml = Class.forName("net.neoforged.fml.loading.FMLLoader");
            if (!(boolean) fml.getMethod("isProduction").invoke(null)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> fml = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
            if (!(boolean) fml.getMethod("isProduction").invoke(null)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(ModConfig.INSTANCE);
        File configFile = new File("config/reflex.json");
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(json);
            }
            // 自己写的这次不算"外部改动", 刷新观测时间避免立刻触发一次多余的热重载
            lastSeenMtime = currentMtime();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 每次改动落盘后把完整配置写入日志, 便于事后确认当时的开关状态
        try {
            io.tythee.ReflexClient.LOGGER.info(INSTANCE.toLogString());
        } catch (Throwable ignored) {
        }
    }
}