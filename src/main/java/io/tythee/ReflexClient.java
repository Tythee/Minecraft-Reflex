package io.tythee;

//? if gte_1_17 {
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//?} else {
/*import org.apache.logging.log4j.LogManager;
*///?}

public class ReflexClient {
    private static final ReflexScheduler SCHEDULER = new ReflexScheduler();
    public static final String MOD_ID = "reflex";

    // 1.17 起 Minecraft 才自带 slf4j; 1.16.5 及更早只有 log4j2(MC 一直自带)。
    // 两个门面的 info(String) 与 info(String, Object...) 签名一致, 因此调用点无需分版本。
    //? if gte_1_17 {
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    //?} else {
    /*public static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger(MOD_ID);
    *///?}

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
