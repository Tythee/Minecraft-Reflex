package io.tythee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReflexClient {
    private static final ReflexScheduler SCHEDULER = new ReflexScheduler();
    public static final String MOD_ID = "reflex";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String VERSION = /*$ mod_version*/ "1.1.2";
    public static final String MINECRAFT = /*$ minecraft*/ "26.2";

    public static void init() {
        LOGGER.info("[Reflex] Initialized on MC {} | Version: {}", MINECRAFT, VERSION);
        LOGGER.info(io.tythee.config.ModConfig.INSTANCE.toLogString());
    }

    public static ReflexScheduler getScheduler() {
        return SCHEDULER;
    }
}
