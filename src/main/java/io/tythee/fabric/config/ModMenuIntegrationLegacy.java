package io.tythee.fabric.config;

//? if fabric {
import io.github.prospector.modmenu.api.ConfigScreenFactory;
import io.github.prospector.modmenu.api.ModMenuApi;

import io.tythee.config.ConfigScreen;

/**
 * ModMenu 集成（1.15 及更早）。
 *
 * 当年 ModMenu 还在 io.github.prospector 名下，1.16 起才迁到 com.terraformersmc；
 * 两个包的接口形状一致（都是 getModConfigScreenFactory() 返回 ConfigScreenFactory<?>），
 * 所以这里只换包名，实现照旧。Gradle 侧按版本二选一编译，避免同时存在两个 ModMenuApi 实现。
 */
public class ModMenuIntegrationLegacy implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::create;
    }
}
//?}
