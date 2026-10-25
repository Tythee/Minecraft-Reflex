package io.tythee.forge;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import io.tythee.ReflexClient;

/**
 * Forge 1.7.10 入口 —— 与 ReflexClientForge 分文件, 由 legacy 子构建按版本二选一。
 *
 * 1.7.10 的 FML 包名是 cpw.mods.fml.*, 1.8.9 起才迁到 net.minecraftforge.fml.*;
 * 两套注解形状一致, 所以这里只换包名。
 *
 * 该版本同样没有 Cloth Config, 配置只走 config/reflex.json。
 */
@Mod(
        modid = ReflexClient.MOD_ID,
        name = "Reflex AntiLag",
        version = ReflexClient.VERSION,
        acceptedMinecraftVersions = "[1.7.10]"
)
public class ReflexClientForge1710 {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ReflexClient.init();
    }
}
