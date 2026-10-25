package io.tythee.neoforge;

//? if neoforge {
/*import net.minecraft.client.gui.screens.Screen;
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
            // IConfigScreenFactory#createScreen 的首个参数在 1.21 从 Minecraft 换成了 ModContainer。
            // 两个重载都写: 每个版本只有其中一个是抽象方法, 另一个只是无害的多余重载。
            // 注意不能加 @Override —— 在另一个版本上它并不是覆盖, 加了会编译失败。
            // (此处刻意不用 //? if 版本分支: 该写法会嵌在加载器条件块内部, 注释嵌套会破坏文件。)
            public Screen createScreen(net.minecraft.client.Minecraft minecraft, Screen modListScreen) {
                return ConfigScreen.create(modListScreen);
            }

            public Screen createScreen(ModContainer modContainer, Screen modListScreen) {
                return ConfigScreen.create(modListScreen);
            }
        });
    }
}
*///?}
