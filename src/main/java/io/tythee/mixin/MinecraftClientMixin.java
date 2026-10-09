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
    private static final String GAME_RENDERER_RENDER = /*$ game_renderer_render_target*/ "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V";

    @Inject(
            method = "run",
            at = @At(value = "INVOKE", target = FRAME_START_TARGET)
    )
    private void onFrameStart(CallbackInfo ci) {
        long sleepNs = ReflexClient.getScheduler().sleep();
        //? if lt_26 {
        /*org.lwjgl.glfw.GLFW.glfwPollEvents();*///?}
        long sleepReturnTime = System.nanoTime();
        ReflexClient.getScheduler().startFrame(sleepReturnTime, sleepNs);
    }

    // INPUT_SAMPLE: 26+ 的 FRAME_START_TARGET 就是 pollEvents, AFTER 即输入轮询完成时刻。
    // pre-26 的 FRAME_START_TARGET 是 runTick 调用点, AFTER 会落到整帧末尾, 故排除 ——
    // 那边 onFrameStart 已自行 glfwPollEvents() 后取 sleepReturnTime 充当采样点。
    //? if !lt_26 {
    @Inject(
            method = "run",
            at = @At(value = "INVOKE", target = FRAME_START_TARGET, shift = At.Shift.AFTER)
    )
    private void afterInputPoll(CallbackInfo ci) {
        ReflexClient.getScheduler().afterInputPoll(System.nanoTime());
    }
    //?}

    // SIMULATION_START: Minecraft#tick() 是四个版本组一致的模拟入口(1.21.11/26.1.2/26.2/26.3
    // 均有 tick()V, 且 runTick 内每帧仅调用一次)。26+ 的 tick() 在 runTick 里而渲染在 renderFrame 里,
    // 所以不能拿 renderFrame 的 HEAD 当模拟起点 —— 那时模拟已经结束了。
    @Inject(method = "tick", at = @At("HEAD"))
    private void onSimulationStart(CallbackInfo ci) {
        ReflexClient.getScheduler().beforeSimulation();
    }

    // SIMULATION_END: 用 RETURN 而非 TAIL —— 四个版本组的 tick() 实测都只有 1 个 return
    // 指令, 两者等价; 但若未来版本加入早退分支, RETURN 仍能覆盖所有出口而 TAIL 会静默漏打。
    @Inject(method = "tick", at = @At("RETURN"))
    private void onSimulationEnd(CallbackInfo ci) {
        ReflexClient.getScheduler().afterSimulation();
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(
                    value = "INVOKE",
                    target = GAME_RENDERER_RENDER
            )
    )
    private void beforeRenderSubmit(CallbackInfo ci) {
        ReflexClient.getScheduler().beforeRenderSubmit();
    }

    @Inject(
            method = RENDER_FRAME,
            at = @At(
                    value = "INVOKE",
                    target = GAME_RENDERER_RENDER,
                    shift = At.Shift.AFTER
            )
    )
    private void afterRenderSubmit(CallbackInfo ci) {
        ReflexClient.getScheduler().afterRenderSubmit();
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
        long presentEndTime = System.nanoTime();
        ReflexClient.getScheduler().endFrame(presentEndTime);
    }
}
