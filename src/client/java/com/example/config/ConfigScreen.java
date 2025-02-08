package com.example.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ConfigScreen {

    public static Screen create(Screen parent) {
        // 创建配置界面构建器
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.nullToEmpty("ReflexMod 配置"));

        // 保存配置时调用
        // 在此处写入将配置保存到文件的逻辑（例如使用 Gson 或其他方式）
        builder.setSavingRunnable(ModConfig::save);

        // 获取或创建一个配置分类（例如“常规设置”）
        ConfigCategory generalCategory = builder.getOrCreateCategory(Component.nullToEmpty("常规设置"));

        // 创建条目构造器
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // 添加一个布尔型开关选项，名称为“reflex是否开启”
        generalCategory.addEntry(
                entryBuilder
                        .startBooleanToggle(Component.nullToEmpty("reflex是否开启"), ModConfig.INSTANCE.isReflexEnabled())
                        .setDefaultValue(true)
                        .setSaveConsumer(ModConfig.INSTANCE::setReflexEnabled)
                        .build());

        // 添加一个数值选项，名称为“减少等待时间”
        generalCategory.addEntry(
                entryBuilder
                        .startLongField(Component.nullToEmpty("减少等待时间"), ModConfig.INSTANCE.getReduceWaitTime())
                        .setDefaultValue(0)
                        .setSaveConsumer(ModConfig.INSTANCE::setReduceWaitTime)
                        .build());

        return builder.build();
    }
}
