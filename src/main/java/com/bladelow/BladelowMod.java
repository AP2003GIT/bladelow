package com.bladelow;

import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.command.BladePlaceCommand;
import com.bladelow.command.BladeAutoCommand;
import com.bladelow.auto.CityAutoplayDirector;
import com.bladelow.ml.BladelowLearning;
import com.bladelow.ml.ManualBuildLearningTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side bootstrap for Bladelow.
 *
 * This wires command registration, background build tickers, checkpoint
 * restore/save, and local learning-state initialization into Fabric's lifecycle.
 */
public class BladelowMod implements ModInitializer {
    public static final String MOD_ID = "bladelow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Register both the direct builder command set and the higher-level
        // automation commands during the normal server command bootstrap.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            BladePlaceCommand.register(dispatcher);
            BladeAutoCommand.register(dispatcher);
        });
        // Active jobs and autoplay sessions are advanced only from the server
        // tick thread so world changes stay serialized and deterministic.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PlacementJobRunner.tick(server);
            CityAutoplayDirector.tick(server);
            ManualBuildLearningTracker.tick(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Bladelow blueprint {}", BlueprintLibrary.reload(server));
            int restored = PlacementJobRunner.restoreFromCheckpoint(server);
            if (restored > 0) {
                LOGGER.info("Bladelow restored paused jobs={}", restored);
            }
            int restoredDirector = CityAutoplayDirector.restore(server);
            if (restoredDirector > 0) {
                LOGGER.info("Bladelow restored city autoplay sessions={}", restoredDirector);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PlacementJobRunner.saveCheckpoint(server);
            CityAutoplayDirector.save(server);
        });

        String loadStatus = BladelowLearning.load();
        LOGGER.info("Bladelow model {}", loadStatus);
        LOGGER.info("Bladelow Builder initialized.");
    }
}
