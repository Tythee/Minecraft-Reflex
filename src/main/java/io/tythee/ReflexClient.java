package io.tythee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;

public class ReflexClient implements ClientModInitializer {
    private static final ReflexScheduler SCHEDULER = new ReflexScheduler();
	public static final String MOD_ID = "reflex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
    }

    public static ReflexScheduler getScheduler() {
        return SCHEDULER;
    }
}
