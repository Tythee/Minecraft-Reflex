package io.tythee.config;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

import com.google.gson.Gson;

public class ModConfig {
    public static ModConfig INSTANCE = load();

    private boolean reflexEnabled = true;

    public boolean isReflexEnabled() {
        return reflexEnabled;
    }

    public void setReflexEnabled(boolean enabled) {
        this.reflexEnabled = enabled;
    }

    private long reduceWaitTime = 0;

    public long getReduceWaitTime() {
        return reduceWaitTime;
    }

    public void setReduceWaitTime(long reduceWaitTime) {
        this.reduceWaitTime = reduceWaitTime;
    }

    public static ModConfig load() {
        File configFile = new File("config/reflex.json");
        if (configFile.exists()) {
            try {
                Gson gson = new Gson();
                String json = new String(Files.readAllBytes(configFile.toPath()));
                return gson.fromJson(json, ModConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return new ModConfig();
    }

    public static void save() {
        Gson gson = new Gson();
        String json = gson.toJson(ModConfig.INSTANCE);
        File configFile = new File("config/reflex.json");
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
