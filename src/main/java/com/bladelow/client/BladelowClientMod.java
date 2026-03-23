package com.bladelow.client;

import com.bladelow.client.ui.BladelowHudScreen;
import com.bladelow.network.HudAction;
import com.bladelow.network.HudCommandBridge;
import com.bladelow.network.HudCommandPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

/**
 * Client bootstrap for the Bladelow HUD, telemetry, and minimal recovery
 * shortcuts.
 *
 * The player-facing workflow is now HUD-first. Hash shortcuts remain only for
 * the manual recovery surface: pause, continue, cancel, and status.
 */
public class BladelowClientMod implements ClientModInitializer {
    private static final String KEY_OPEN_HUD = "key.bladelow.open_hud";
    private static final Set<String> HASH_COMMAND_ROOTS = Set.of(
        "bladecancel",
        "bladepause",
        "bladecontinue",
        "bladestatus"
    );

    private static KeyBinding openHudKey;

    @Override
    public void onInitializeClient() {
        openHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            KEY_OPEN_HUD,
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            KeyBinding.Category.MISC
        ));

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) ->
            BladelowHudTelemetry.recordServerMessage(message.getString())
        );
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
            BladelowHudTelemetry.recordServerMessage(message.getString())
        );

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message == null ? "" : message.trim();
            if (!trimmed.startsWith("#")) {
                return true;
            }

            String command = trimmed.substring(1).trim();
            if (command.isEmpty()) {
                return true;
            }
            if (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            if (command.isEmpty()) {
                return true;
            }

            HudCommandPayload payload = parseRecoveryShortcut(command);
            if (payload == null || !HASH_COMMAND_ROOTS.contains(firstToken(command))) {
                return true;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.player.networkHandler == null) {
                return false;
            }

            BladelowSelectionOverlay.applyHudAction(payload);
            if (HudCommandBridge.sendClientPayload(payload)) {
                BladelowHudTelemetry.recordLocalMessage("[hud] " + payload.describe());
                client.player.sendMessage(Text.literal("[Bladelow] ran: " + payload.describe()).formatted(Formatting.AQUA), false);
                return false;
            }

            BladelowHudTelemetry.recordLocalMessage("[bridge-unavailable] " + payload.describe());
            client.player.sendMessage(
                Text.literal("[Bladelow] HUD bridge unavailable; recovery action not sent: " + payload.describe())
                    .formatted(Formatting.RED),
                false
            );
            return false;
        });
    }

    public static void onClientTick(MinecraftClient client) {
        if (openHudKey == null) {
            return;
        }
        BladelowSelectionOverlay.tick(client);

        while (openHudKey.wasPressed()) {
            if (client.currentScreen instanceof BladelowHudScreen) {
                client.setScreen(null);
            } else {
                client.setScreen(new BladelowHudScreen());
            }
        }
    }

    private static HudCommandPayload parseRecoveryShortcut(String command) {
        if (command == null) {
            return null;
        }
        String normalized = command.trim().toLowerCase();
        return switch (normalized) {
            case "bladepause" -> HudCommandPayload.of(HudAction.PAUSE_BUILD);
            case "bladecontinue" -> HudCommandPayload.of(HudAction.CONTINUE_BUILD);
            case "bladecancel" -> HudCommandPayload.of(HudAction.CANCEL_BUILD);
            case "bladestatus" -> HudCommandPayload.of(HudAction.STATUS);
            case "bladestatus detail" -> HudCommandPayload.of(HudAction.STATUS_DETAIL);
            default -> null;
        };
    }

    private static String firstToken(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String[] parts = command.trim().toLowerCase().split("\\s+", 2);
        return parts.length == 0 ? "" : parts[0];
    }
}
