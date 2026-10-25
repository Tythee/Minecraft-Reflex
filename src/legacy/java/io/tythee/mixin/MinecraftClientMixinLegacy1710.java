package io.tythee.mixin;

import io.tythee.ReflexClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7.10(Forge, MCP 12-1.7.10)的帧钩子 —— 与 1.8.9+ 的 Legacy 实现分文件, 由 mixins_client 选择。
 *
 * 1.7.10 与 1.8.9 的三处结构差异(逐条反编译确认):
 *   1) 渲染提交点是 EntityRenderer#updateCameraAndRender(F)V —— 只有 float, 没有 long 参数;
 *   2) 没有 Minecraft#updateDisplay(): 每帧的呈递来自 runGameLoop() 调用 Minecraft#resetSize()(@511),
 *      而 resetSize() 内部才调 org.lwjgl.opengl.Display.update() —— 所以呈现桩挂 resetSize() 调用点;
 *   3) 没有 GuiOverlayDebug, F3 左栏由 Minecraft#displayDebugInfo(long) 就地组列表并绘制,
 *      需要一个额外的注入设计, 本版本先不做面板(指标仍可经诊断日志查看) —— 见 TODO。
 * 相同之处: 帧循环仍是 runGameLoop(), 帧起点仍是它内部的 runTick() 调用点(@131)。
 *
 * 源码写 MCP 名字, 由 Unimined 的 refmap 打包时映射为生产环境的 SRG 名字。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixinLegacy1710 {
    /** 帧循环方法(内含三个桩位的调用点)。 */
    private static final String FRAME_LOOP = "runGameLoop()V";

    /** 帧起点: runGameLoop() 调 runTick() 的瞬间 —— 输入轮询就在 runTick 里。 */
    private static final String FRAME_START_TARGET = "Lnet/minecraft/client/Minecraft;runTick()V";

    /** RENDER_SUBMIT: 交给 EntityRenderer 渲染一帧(1.7.10 只有 float 形参)。 */
    private static final String RENDER_TARGET = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(F)V";

    /** PRESENT: runGameLoop() 调 resetSize() 的瞬间, 其内部即 Display.update() 交换缓冲。 */
    private static final String PRESENT_TARGET = "Lnet/minecraft/client/Minecraft;resetSize()V";

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
