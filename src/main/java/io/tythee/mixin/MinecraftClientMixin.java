package io.tythee.mixin;

import io.tythee.CpuTimeCollector;
import io.tythee.ReflexClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    @Unique
    private final CpuTimeCollector cpuTimeCollect = new CpuTimeCollector();

    @Inject(method = "runTick", at = @At(value = "HEAD", shift = At.Shift.AFTER))
    private void afterRunTick(boolean bl, CallbackInfo ci) {
        ReflexClient.getScheduler().Wait();

        cpuTimeCollect.startCollect();
        ReflexClient.getScheduler().renderQueueAdd();
    }


    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
    private void beforeFlush(CallbackInfo ci) {
        ReflexClient.getScheduler().renderQueueEndInsert();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V", shift = At.Shift.AFTER))
    private void afterFlush(CallbackInfo ci) {
        Long cpuTime = null;

        if (!ReflexClient.getScheduler().gpuTimeCollectorDeque.isEmpty()) {
            ReflexClient.getScheduler().gpuTimeCollectorDeque.getFirst().startQueryCheck();
        }
        if(!ReflexClient.getScheduler().gpuTimeCollectorDeque.isEmpty() && ReflexClient.getScheduler().gpuTimeCollectorDeque.getFirst().startTimeSystem != null){
            if (cpuTimeCollect.startTime != null) {
                cpuTime = ReflexClient.getScheduler().gpuTimeCollectorDeque.getFirst().startTimeSystem - cpuTimeCollect.startTime;
            }
        }else{
            cpuTimeCollect.endCollect();
            cpuTime = cpuTimeCollect.getCpuTime();
        }

        cpuTimeCollect.reset();
        if (cpuTime != null) {
            ReflexClient.getScheduler().updateCpuTime(cpuTime);
        }
    }
}