package com.example;

import net.fabricmc.api.ClientModInitializer;

// ReflexMod.java - 主类
public class ReflexMod implements ClientModInitializer {
    private static final ReflexScheduler SCHEDULER = new ReflexScheduler();

    @Override
    public void onInitializeClient() {
    }

    public static ReflexScheduler getScheduler() {
        return SCHEDULER;
    }
}
