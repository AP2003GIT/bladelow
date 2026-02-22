package com.bladelow;

import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.command.BladePlaceCommand;
import com.bladelow.ml.BladelowLearning;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BladelowMod implements ModInitializer {
    public static final String MOD_ID = "bladelow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            BladePlaceCommand.register(dispatcher)
        );
        ServerTickEvents.END_SERVER_TICK.register(PlacementJobRunner::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> LOGGER.info("Bladelow blueprint {}", BlueprintLibrary.reload(server)));

        String loadStatus = BladelowLearning.load();
        LOGGER.info("Bladelow model {}", loadStatus);
        LOGGER.info("Bladelow Builder initialized.");
    }
}
