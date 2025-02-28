package io.tythee.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ConfigScreen {

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.nullToEmpty("ReflexClient 配置"));

        builder.setSavingRunnable(ModConfig::save);

        ConfigCategory generalCategory = builder.getOrCreateCategory(Component.nullToEmpty("常规设置"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        generalCategory.addEntry(
                entryBuilder
                        .startBooleanToggle(Component.nullToEmpty("reflex是否开启"), ModConfig.INSTANCE.isReflexEnabled())
                        .setDefaultValue(true)
                        .setSaveConsumer(ModConfig.INSTANCE::setReflexEnabled)
                        .build());

        generalCategory.addEntry(
                entryBuilder
                        .startLongField(Component.nullToEmpty("减少等待时间"), ModConfig.INSTANCE.getReduceWaitTime())
                        .setDefaultValue(0)
                        .setSaveConsumer(ModConfig.INSTANCE::setReduceWaitTime)
                        .build());

        return builder.build();
    }
}
