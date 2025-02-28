package io.tythee;

import net.fabricmc.api.ClientModInitializer;

public class ReflexClient implements ClientModInitializer {
	public static final String MOD_ID = "reflex";
    private static final ReflexScheduler SCHEDULER = new ReflexScheduler();

    @Override
    public void onInitializeClient() {
    }

    public static ReflexScheduler getScheduler() {
        return SCHEDULER;
    }
}
