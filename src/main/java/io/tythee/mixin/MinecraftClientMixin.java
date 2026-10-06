package io.tythee.mixin;

import io.tythee.ReflexClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    // Mojang renamed this method from runTick(Z)V (pre-26.1) to renderFrame(Z)V (26.1+)
    @Unique
    private static final String RENDER_FRAME = /*$ render_frame_method*/ "renderFrame(Z)V";

    // Call site immediately before input events are polled
    // Pre-26: runTick(Z)V; 26.0+: RenderSystem#pollEvents()V
    @Unique
    private static final String FRAME_START_TARGET = /*$ frame_start_target*/ "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents()V";

    // The frame-present call site keeps getting renamed by Mojang release to release:
    // Window#updateDisplay (<26), RenderSystem#flipFrame (26.1.x), GpuSurface#present (26.2+).
    @Unique
    private static final String FLIP_FRAME_TARGET = /*$ flip_frame_target*/ "Lcom/mojang/blaze3d/systems/GpuSurface;present()V";

    @Unique
    private static final String GAME_RENDERER_RENDER = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V";

    @Inject(
            method = "run",
            at = @At(value = "INVOKE", target = FRAME_START_TARGET)
    )
    private void onFrameStart(CallbackInfo ci) {
        long waitNs = ReflexClient.getScheduler().Wait();
        //? if lt_26 {
        /*org.lwjgl.glfw.GLFW.glfwPollEvents();*///?}
        long cpuStartTime = System.nanoTime();
        ReflexClient.getScheduler().startFrame(cpuStartTime, waitNs);
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(
                    value = "INVOKE",
                    target = GAME_RENDERER_RENDER
            )
    )
    private void beforeRenderBuild(CallbackInfo ci) {
        ReflexClient.getScheduler().beforeRenderBuild();
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(
                    value = "INVOKE",
                    target = GAME_RENDERER_RENDER,
                    shift = At.Shift.AFTER
            )
    )
    private void afterRenderBuild(CallbackInfo ci) {
        ReflexClient.getScheduler().afterRenderBuild();
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(value = "INVOKE", target = FLIP_FRAME_TARGET)
    )
    private void beforePresent(CallbackInfo ci) {
        ReflexClient.getScheduler().beforePresent();
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(value = "INVOKE", target = FLIP_FRAME_TARGET, shift = At.Shift.AFTER)
    )
    private void afterPresent(CallbackInfo ci) {
        long cpuEndTime = System.nanoTime();
        ReflexClient.getScheduler().endFrame(cpuEndTime);
    }
}
