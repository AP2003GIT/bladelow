package com.bladelow;

import com.bladelow.auto.CityAutoplayDirector;
import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.command.ManualRecoveryCommands;
import com.bladelow.ml.BladelowLearning;
import com.bladelow.ml.ManualBuildLearningTracker;
import com.bladelow.network.HudCommandBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side bootstrap for Bladelow.
 *
 * Normal planning/build actions now flow through the HUD packet bridge, while a
 * tiny chat-command recovery surface remains available for pause/continue/
 * cancel/status when something needs manual intervention.
 */
public class BladelowMod implements ModInitializer {
    public static final String MOD_ID = "bladelow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            ManualRecoveryCommands.register(dispatcher)
        );
        HudCommandBridge.registerServer();
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
