package io.tythee.mixin;

import io.tythee.ReflexMetrics;
import io.tythee.config.ModConfig;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugOverlayMixin {
    //? if gte_26_1 {
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
