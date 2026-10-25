package io.tythee.mixin;

import io.tythee.ReflexMetrics;
import io.tythee.config.ModConfig;
import net.minecraft.client.gui.GuiOverlayDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 1.12.2 的 F3 面板注入 —— 与 DebugOverlayMixin 分文件, 由 reflex.mixins.json 二选一。
 *
 * 1.12.2 的调试面板是 net.minecraft.client.gui.GuiOverlayDebug:
 *   - renderDebugInfoLeft() 负责左栏, 而左栏内容来自 call()(反编译确认 renderDebugInfoLeft 首条指令即 call());
 *   - 因此挂在 call() 的返回处追加指标行, 与 1.17~1.19 挂 getSystemInformation 同理。
 *
 * 本文件位于 src/legacy, 不经 stonecutter 处理。
 */
@Mixin(GuiOverlayDebug.class)
public abstract class DebugOverlayMixinLegacy {

    @Inject(method = "call", at = @At("RETURN"), cancellable = true)
    private void onCall(CallbackInfoReturnable<List<String>> cir) {
        if (ModConfig.INSTANCE.isShowLatencyMetrics()) {
            List<String> lines = new java.util.ArrayList<String>(cir.getReturnValue());
            lines.addAll(ReflexMetrics.getInstance().getMetricsLines());
            cir.setReturnValue(lines);
        }
    }
}
