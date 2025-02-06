package com.example.mixin.client;

import com.example.CpuTimeCollector;
import com.example.GpuTimeCollector;
import com.example.ReflexMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.example.Reflex.MOD_ID;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    @Unique
    private final CpuTimeCollector cpuTimeCollect = new CpuTimeCollector();
    @Unique
    private final List<GpuTimeCollector> gpuTimeCollectorList = new ArrayList<>();

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setErrorSection(Ljava/lang/String;)V", ordinal = 0))
    private void beforeRunTick(boolean bl, CallbackInfo ci) {
        ReflexMod.getScheduler().Wait();

        cpuTimeCollect.startCollect();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;getNanos()J", // 目标方法签名
            ordinal = 0 // 如果多次调用 clear，用 ordinal 指定第几次调用
    ))
    private void beforeRender(boolean bl, CallbackInfo ci) {
        ReflexMod.getScheduler().renderQueueAdd();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void afterRender(CallbackInfo ci) {
        ReflexMod.getScheduler().renderQueueEndInsert();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V", shift = At.Shift.AFTER))
    private void afterFlush(CallbackInfo ci) {
        cpuTimeCollect.endCollect();
        Long cpuTime = cpuTimeCollect.getCpuTime();
        cpuTimeCollect.reset();
        if (cpuTime != null) {
            // 更新 CPU 预测时间
            ReflexMod.getScheduler().updateCpuTime(cpuTime);
        }

        ReflexMod.getScheduler().renderQueueStartWait();
    }
}