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
                .setTitle(Component.literal("Reflex AntiLag 设置"));

        builder.setSavingRunnable(ModConfig::save);

        ConfigCategory generalCategory = builder.getOrCreateCategory(Component.literal("常规设置"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                                Component.literal("启用 Reflex"),
                                ModConfig.INSTANCE.isReflexEnabled())
                        .setDefaultValue(true)
                        .setTooltip(Component.literal("开启或关闭基于 Reflex 原理的帧对齐低延迟调度"))
                        .setSaveConsumer(ModConfig.INSTANCE::setReflexEnabled)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                                Component.literal("时序流程图展示 (Timeline Diagram)"),
                                ModConfig.INSTANCE.isTimelineDiagram())
                        .setDefaultValue(true)
                        .setTooltip(Component.literal("在 F3 调试面板中以多行甘特图逐帧展示各标记的实测位置与对齐质量"))
                        .setSaveConsumer(ModConfig.INSTANCE::setTimelineDiagram)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                                Component.literal("显示实时延迟指标 (Reflex Metrics)"),
                                ModConfig.INSTANCE.isShowLatencyMetrics())
                        .setDefaultValue(true)
                        .setTooltip(Component.literal("在 F3 调试面板中显示 PC 总延迟、Sleep 时长、对齐误差与各标记区间的实测耗时"))
                        .setSaveConsumer(ModConfig.INSTANCE::setShowLatencyMetrics)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                                Component.literal("启用诊断日志 (Diagnostic Logging)"),
                                ModConfig.INSTANCE.isEnableDiagnosticLogging())
                        .setDefaultValue(ModConfig.isDevEnvironment())
                        .setTooltip(Component.literal("每秒向游戏控制台输出 Summary 总览与 Internals 休眠决策全过程; GPU 时间戳就绪后另加 Timestamps 逐标记时刻（开发环境默认开启，生产环境默认关闭）"))
                        .setSaveConsumer(ModConfig.INSTANCE::setEnableDiagnosticLogging)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startDoubleField(
                                Component.literal("手动休眠偏置 (毫秒)"),
                                ModConfig.INSTANCE.getManualSleepOffsetMs())
                        .setDefaultValue(0.0d)
                        .setMin(-5.0d)
                        .setMax(5.0d)
                        .setTooltip(Component.literal("在算出的休眠时长上叠加手动偏置：正数推迟唤醒，负数提前唤醒（提前量以完全不休眠为下限）"))
                        .setSaveConsumer(ModConfig.INSTANCE::setManualSleepOffsetMs)
                        .build());

        return builder.build();
    }
}