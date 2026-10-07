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
                                Component.literal("自适应动态闭环 (Adaptive Offset)"),
                                ModConfig.INSTANCE.isAdaptiveOffset())
                        .setDefaultValue(true)
                        .setTooltip(Component.literal("自动检测 GPU 饥饿与队列堆积，动态微调等待偏置（开启时将覆盖手动偏置）"))
                        .setSaveConsumer(ModConfig.INSTANCE::setAdaptiveOffset)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                                Component.literal("时序流程图展示 (Timeline Diagram)"),
                                ModConfig.INSTANCE.isTimelineDiagram())
                        .setDefaultValue(true)
                        .setTooltip(Component.literal("在 F3 调试面板中以多行流水线时序图直观展示 CPU 与 GPU 的对齐与排队状态"))
                        .setSaveConsumer(ModConfig.INSTANCE::setTimelineDiagram)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                                Component.literal("显示实时延迟指标 (Reflex Metrics)"),
                                ModConfig.INSTANCE.isShowLatencyMetrics())
                        .setDefaultValue(true)
                        .setTooltip(Component.literal("在 F3 调试面板中显示 Game、Render、Overlap 与 PC 总延迟"))
                        .setSaveConsumer(ModConfig.INSTANCE::setShowLatencyMetrics)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                                Component.literal("启用诊断日志 (Diagnostic Logging)"),
                                ModConfig.INSTANCE.isEnableDiagnosticLogging())
                        .setDefaultValue(ModConfig.isDevEnvironment())
                        .setTooltip(Component.literal("每秒聚合输出一次 Reflex Summary 统计日志至游戏控制台（开发环境默认开启，生产环境默认关闭）"))
                        .setSaveConsumer(ModConfig.INSTANCE::setEnableDiagnosticLogging)
                        .build());

        generalCategory.addEntry(
                entryBuilder.startDoubleField(
                                Component.literal("手动等待偏置 (毫秒)"),
                                ModConfig.INSTANCE.getManualWaitOffsetMs())
                        .setDefaultValue(0.0d)
                        .setMin(-5.0d)
                        .setMax(5.0d)
                        .setTooltip(Component.literal("为计算出的等待时间增加手动偏置，正数增加等待时间，负数减少等待时间 (单位: ms)"))
                        .setSaveConsumer(ModConfig.INSTANCE::setManualWaitOffsetMs)
                        .build());

        return builder.build();
    }
}