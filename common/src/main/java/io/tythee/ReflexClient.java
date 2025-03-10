package io.tythee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReflexClient {
    private static final ReflexScheduler SCHEDULER = new ReflexScheduler();
	public static final String MOD_ID = "reflex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("ReflexClient initialized");
    }

    public static ReflexScheduler getScheduler() {
        return SCHEDULER;
    }
}
