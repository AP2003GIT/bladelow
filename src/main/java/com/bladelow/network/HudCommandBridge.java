package com.bladelow.network;

import com.bladelow.BladelowMod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Packet bridge between the client HUD and server-side action handlers.
 *
 * The transport stays packet-based, and the payload itself is action-native:
 * one explicit HUD action enum plus action-specific arguments.
 */
public final class HudCommandBridge {
    private HudCommandBridge() {
    }

    public static void registerServer() {
        PayloadTypeRegistry.playC2S().register(HudCommandPayload.ID, HudCommandPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HudCommandPayload.ID, (payload, context) ->
            executeHudAction(context.player(), payload)
        );
    }

    public static boolean sendClientAction(HudAction action, String... args) {
        return sendClientPayload(HudCommandPayload.of(action, args));
    }

    public static boolean sendClientPayload(HudCommandPayload payload) {
        if (payload == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            return false;
        }
        if (!ClientPlayNetworking.canSend(HudCommandPayload.ID)) {
            return false;
        }
        ClientPlayNetworking.send(payload);
        return true;
    }

    private static void executeHudAction(ServerPlayerEntity player, HudCommandPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        boolean handled = HudActionService.execute(player, payload);
        if (!handled) {
            BladelowMod.LOGGER.warn(
                "Rejected unsupported HUD action from {}: {}",
                player.getName().getString(),
                payload.describe()
            );
        }
    }
}
