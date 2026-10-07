package io.tythee.config;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ModConfig {
    public static ModConfig INSTANCE = load();

    private boolean reflexEnabled = true;
    private boolean adaptiveOffset = true;
    private boolean showLatencyMetrics = true;
    private boolean timelineDiagram = true;
    private boolean enableDiagnosticLogging = isDevEnvironment();
    private double manualWaitOffsetMs = 0.0;

    public boolean isReflexEnabled() {
        return reflexEnabled;
    }

    public void setReflexEnabled(boolean enabled) {
        this.reflexEnabled = enabled;
    }

    public boolean isAdaptiveOffset() {
        return adaptiveOffset;
    }

    public void setAdaptiveOffset(boolean adaptiveOffset) {
        this.adaptiveOffset = adaptiveOffset;
    }

    // Alias for backward compatibility
    public boolean isAdaptiveMargin() {
        return isAdaptiveOffset();
    }

    public void setAdaptiveMargin(boolean adaptiveMargin) {
        setAdaptiveOffset(adaptiveMargin);
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

    public double getManualWaitOffsetMs() {
        return manualWaitOffsetMs;
    }

    public void setManualWaitOffsetMs(double manualWaitOffsetMs) {
        this.manualWaitOffsetMs = manualWaitOffsetMs;
    }

    public long getManualWaitOffsetNs() {
        return (long) (manualWaitOffsetMs * 1_000_000.0);
    }

    public void setManualWaitOffsetNs(long manualWaitOffsetNs) {
        this.manualWaitOffsetMs = manualWaitOffsetNs / 1_000_000.0;
    }

    public static ModConfig load() {
        File configFile = new File("config/reflex.json");
        if (configFile.exists()) {
            try {
                Gson gson = new Gson();
                String json = new String(Files.readAllBytes(configFile.toPath()));
                ModConfig config = gson.fromJson(json, ModConfig.class);
                if (config != null) {
                    if (config.manualWaitOffsetMs == 0.0 && json.contains("\"manualWaitOffsetNs\"")) {
                        try {
                            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                            if (obj.has("manualWaitOffsetNs") && !obj.has("manualWaitOffsetMs")) {
                                config.manualWaitOffsetMs = obj.get("manualWaitOffsetNs").getAsLong() / 1_000_000.0;
                            }
                            if (obj.has("adaptiveMargin") && !obj.has("adaptiveOffset")) {
                                config.adaptiveOffset = obj.get("adaptiveMargin").getAsBoolean();
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

    public static boolean isDevEnvironment() {
        try {
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}