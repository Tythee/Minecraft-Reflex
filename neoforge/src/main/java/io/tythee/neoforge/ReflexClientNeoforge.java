package io.tythee.neoforge;

import net.minecraft.client.gui.screen.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import io.tythee.ReflexClient;
import io.tythee.config.ConfigScreen;

@Mod(value = ReflexClient.MOD_ID, dist = Dist.CLIENT)
public final class ReflexClientNeoforge {
    public ReflexClientNeoforge(ModContainer container) {
        ReflexClient.init();
        container.registerExtensionPoint(IConfigScreenFactory.class, new IConfigScreenFactory() {
            @SuppressWarnings("null")
            @Override
            public Screen createScreen(ModContainer container, Screen modListScreen) {
                return ConfigScreen.create(modListScreen);
            }
        });
    }
}