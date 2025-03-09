package io.tythee.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ConfigScreen {

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("Reflex AntiLag 设置"));

        builder.setSavingRunnable(ModConfig::save);

        ConfigCategory generalCategory = builder.getOrCreateCategory(Text.literal("常规设置"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        generalCategory.addEntry(
                entryBuilder
                        .startBooleanToggle(Text.literal("启用 Reflex"), ModConfig.INSTANCE.isReflexEnabled())
                        .setDefaultValue(true)
                        .setSaveConsumer(ModConfig.INSTANCE::setReflexEnabled)
                        .build());

        generalCategory.addEntry(
                entryBuilder
                        .startLongField(Text.literal("减少等待时间"), ModConfig.INSTANCE.getReduceWaitTime())
                        .setDefaultValue(0)
                        .setSaveConsumer(ModConfig.INSTANCE::setReduceWaitTime)
                        .build());

        return builder.build();
    }
}
