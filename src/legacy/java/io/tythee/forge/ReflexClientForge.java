package io.tythee.forge;

import io.tythee.ReflexClient;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/**
 * Forge 1.12.2 入口。
 *
 * 该版本没有 Cloth Config(它从 1.14 才有), 所以不做图形配置界面:
 * 全部配置项写在 config/reflex.json, 与其它版本的配置项完全一致(见 ModConfig)。
 */
@Mod(
        modid = ReflexClient.MOD_ID,
        name = "Reflex AntiLag",
        version = ReflexClient.VERSION,
        acceptedMinecraftVersions = "[1.12,1.12.2]",
        clientSideOnly = true
)
public class ReflexClientForge {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ReflexClient.init();
    }
}
