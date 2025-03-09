package io.tythee.fabric;

import net.fabricmc.api.ClientModInitializer;

import io.tythee.ReflexClient;

public final class ReflexClientFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ReflexClient.init();    
    }
}
