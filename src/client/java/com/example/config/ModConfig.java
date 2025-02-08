package com.example.config;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

import com.google.gson.Gson;

public class ModConfig {

    // 单例模式保存配置实例
    public static ModConfig INSTANCE = load();

    private boolean reflexEnabled = true; // 默认值

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

    // 加载配置
    public static ModConfig load() {
        // 读取配置文件
        File configFile = new File("config/reflex.json");
        if (configFile.exists()) {
            // 如果文件存在,则加载配置文件中的数据
            try {
                Gson gson = new Gson();
                String json = new String(Files.readAllBytes(configFile.toPath()));
                return gson.fromJson(json, ModConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 如果文件不存在，返回默认配置
        return new ModConfig();
    }

    // 保存配置到文件
    public static void save() {
        // 将 ModConfig.INSTANCE 中的数据保存到磁盘或配置文件中
        // 例如使用 Gson JSON 序列化工具
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
