package io.tythee.mixin;

import io.tythee.ReflexClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.12.2(Forge, MCP 映射)的帧钩子 —— 与 MinecraftClientMixin 分文件, 由 reflex.mixins.json 二选一。
 *
 * 1.12.2 与 1.14+ 的结构差异(逐条由反编译确认):
 *   - 一帧的宿主方法是 Minecraft#runGameLoop(), 不是 runTick(): 渲染与呈递都在帧循环里;
 *   - 呈递点是 Minecraft#updateDisplay()(内部才调 org.lwjgl.opengl.Display.update());
 *   - 渲染提交在 EntityRenderer#updateCameraAndRender(FJ)V(不是 GameRenderer#render);
 *   - 没有 Minecraft#tick(), 故本版本不打 SIMULATION_START/END 桩(下游按 0 兜底: est.sim 与
 *     Simulation 指标为 n/a, 调度本身仍照 CPU 侧推理工作)。
 *
 * 源码写 MCP 名字, 由 Unimined 的 refmap 打包时映射为生产环境的 SRG 名字。
 * 本文件位于 src/legacy, 不经 stonecutter 处理(所以不写 `//? if` 指令)。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixinLegacy {
    /** 帧循环方法(内含三个桩位的调用点)。 */
    private static final String FRAME_LOOP = "runGameLoop()V";

    /** 帧起点: runGameLoop() 调 runTick() 的瞬间 —— 输入轮询就在 runTick 里, 睡在这里等于"睡醒即采输入"。 */
    private static final String FRAME_START_TARGET = "Lnet/minecraft/client/Minecraft;runTick()V";

    /** RENDER_SUBMIT: 交给 EntityRenderer 渲染一帧。 */
    private static final String RENDER_TARGET =
            "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V";

    /** PRESENT: 交换链呈现(Minecraft#updateDisplay 内部调 Display#update)。 */
    private static final String PRESENT_TARGET = "Lnet/minecraft/client/Minecraft;updateDisplay()V";

    @Inject(method = FRAME_LOOP, at = @At(value = "INVOKE", target = FRAME_START_TARGET))
    private void onFrameStart(CallbackInfo ci) {
        long sleepNs = ReflexClient.getScheduler().sleep();
        long sleepReturnTime = System.nanoTime();
        ReflexClient.getScheduler().startFrame(sleepReturnTime, sleepNs);
    }

    @Inject(method = FRAME_LOOP, at = @At(value = "INVOKE", target = RENDER_TARGET))
    private void beforeRenderSubmit(CallbackInfo ci) {
        ReflexClient.getScheduler().beforeRenderSubmit();
    }

    @Inject(method = FRAME_LOOP, at = @At(value = "INVOKE", target = RENDER_TARGET, shift = At.Shift.AFTER))
    private void afterRenderSubmit(CallbackInfo ci) {
        ReflexClient.getScheduler().afterRenderSubmit();
    }

    @Inject(method = FRAME_LOOP, at = @At(value = "INVOKE", target = PRESENT_TARGET))
    private void beforePresent(CallbackInfo ci) {
        ReflexClient.getScheduler().beforePresent();
    }

    @Inject(method = FRAME_LOOP, at = @At(value = "INVOKE", target = PRESENT_TARGET, shift = At.Shift.AFTER))
    private void afterPresent(CallbackInfo ci) {
        long presentEndTime = System.nanoTime();
        ReflexClient.getScheduler().endFrame(presentEndTime);
    }
}
