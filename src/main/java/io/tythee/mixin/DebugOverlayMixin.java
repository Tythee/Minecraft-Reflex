package io.tythee.mixin;

import io.tythee.ReflexMetrics;
import io.tythee.config.ModConfig;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if !gte_1_20 {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugOverlayMixin {
    //? if !gte_1_20 {
    /*// 1.17 ~ 1.19.x 没有 renderLines(GuiGraphics,...) 这一步: 左栏内容由 getSystemInformation()
    // 产出后交给 drawSystemInformation(PoseStack) 画。故在返回处追加本 mod 的指标行,
    // 用 setReturnValue 而不是原地 addAll —— 原实现可能返回不可变列表。
    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void onSystemInformation(CallbackInfoReturnable<List<String>> cir) {
        if (ModConfig.INSTANCE.isShowLatencyMetrics()) {
            List<String> lines = new java.util.ArrayList<>(cir.getReturnValue());
            lines.addAll(ReflexMetrics.getInstance().getMetricsLines());
            cir.setReturnValue(lines);
        }
    }
    *///?} elif gte_26_3 {
    /*@Inject(method = "extractLines", at = @At("HEAD"))
    private void onExtractLines(net.minecraft.client.gui.GuiGraphicsExtractor extractor, List<String> lines, boolean isLeft, int lineLimit, CallbackInfo ci) {
        if (isLeft && ModConfig.INSTANCE.isShowLatencyMetrics()) {
            lines.addAll(ReflexMetrics.getInstance().getMetricsLines());
        }
    }
    *///?} elif gte_26_1 {
    @Inject(method = "extractLines", at = @At("HEAD"))
    private void onExtractLines(net.minecraft.client.gui.GuiGraphicsExtractor extractor, List<String> lines, boolean isLeft, CallbackInfo ci) {
        if (isLeft && ModConfig.INSTANCE.isShowLatencyMetrics()) {
            lines.addAll(ReflexMetrics.getInstance().getMetricsLines());
        }
    }
    //?} else {
    /*@Inject(method = "renderLines", at = @At("HEAD"))
    private void onRenderLines(net.minecraft.client.gui.GuiGraphics graphics, List<String> lines, boolean isLeft, CallbackInfo ci) {
        if (isLeft && ModConfig.INSTANCE.isShowLatencyMetrics()) {
            lines.addAll(ReflexMetrics.getInstance().getMetricsLines());
        }
    }*///?}
}
