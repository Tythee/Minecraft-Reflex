package io.tythee.mixin;

import io.tythee.CpuTimeCollector;
import io.tythee.ReflexClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Unique
    private final CpuTimeCollector cpuTimeCollect = new CpuTimeCollector();

    @Inject(method = "render", at = @At(value = "HEAD", shift = At.Shift.AFTER))
    private void afterRender(boolean bl, CallbackInfo ci) {
        ReflexClient.getScheduler().Wait();

        cpuTimeCollect.startCollect();
        ReflexClient.getScheduler().renderQueueAdd();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;swapBuffers()V"))
    private void beforeFlush(CallbackInfo ci) {
        ReflexClient.getScheduler().renderQueueEndInsert();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;swapBuffers()V", shift = At.Shift.AFTER))
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