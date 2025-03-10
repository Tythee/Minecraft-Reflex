package io.tythee.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import io.tythee.ReflexClient;
import io.tythee.config.ConfigScreen;

@OnlyIn(Dist.CLIENT)
@Mod(ReflexClient.MOD_ID)
public final class ReflexClientForge {
    @SuppressWarnings("removal")
    public ReflexClientForge() {
        ReflexClient.init();
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> {
            return ConfigScreen.create(parent);
        }));
    }
}