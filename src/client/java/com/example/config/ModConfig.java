package com.example.config;

import java.io.File;
import java.io.FileWriter;

import com.google.gson.Gson;

public class ModConfig {

    // 单例模式保存配置实例
    public static final ModConfig INSTANCE = new ModConfig();

    private boolean reflexEnabled = true; // 默认值

    public boolean isReflexEnabled() {
        return reflexEnabled;
    }

    public void setReflexEnabled(boolean enabled) {
        this.reflexEnabled = enabled;
    }

    // 示例：保存配置到文件（你需要根据自己的需求实现读取和保存逻辑）
    public static void save() {
        // 将 ModConfig.INSTANCE 中的数据保存到磁盘或配置文件中
        // 例如使用 Gson JSON 序列化工具
        Gson gson = new Gson();
        String json = gson.toJson(ModConfig.INSTANCE);
        File configFile = new File("config.json");
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(json);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
