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

    // Mojang renamed this method from runTick(Z)V (pre-26.1) to renderFrame(Z)V (26.1+)
    private static final String RENDER_FRAME = /*$ render_frame_method*/ "renderFrame(Z)V";

    // Frame presentation moved from Window#updateDisplay(TracyFrameCapture) (pre-26.1)
    // to RenderSystem#flipFrame(TracyFrameCapture) (26.1+); same call site in the render loop.
    private static final String FLIP_FRAME_TARGET = /*$ flip_frame_target*/ "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V";

    @Inject(method = RENDER_FRAME, at = @At(value = "HEAD", shift = At.Shift.AFTER))
    private void afterRender(boolean bl, CallbackInfo ci) {
        ReflexClient.getScheduler().Wait();

        cpuTimeCollect.startCollect();
        ReflexClient.getScheduler().renderQueueAdd();
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(
                    value = "INVOKE",
                    target = FLIP_FRAME_TARGET
            )
    )
    private void beforeFlush(CallbackInfo ci) {
        ReflexClient.getScheduler().renderQueueEndInsert();
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(
                    value = "INVOKE",
                    target = FLIP_FRAME_TARGET,
                    shift = At.Shift.AFTER
            )
    )
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
