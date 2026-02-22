package com.bladelow.client;

import com.bladelow.client.ui.BladelowHudScreen;
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

import java.util.Locale;
import java.util.Set;

public class BladelowClientMod implements ClientModInitializer {
    private static final String KEY_OPEN_HUD = "key.bladelow.open_hud";
    private static final Set<String> HASH_COMMAND_ROOTS = Set.of(
        "bladehelp",
        "bladeplace",
        "bladeselect",
        "bladecancel",
        "bladeconfirm",
        "bladepreview",
        "bladestatus",
        "blademove",
        "bladesafety",
        "bladeprofile",
        "bladeblueprint",
        "bladeweb",
        "blademodel"
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

            String[] parts = command.split("\\s+", 2);
            String root = parts[0].toLowerCase(Locale.ROOT);
            if ("bladelow".equals(root)) {
                command = "bladehelp";
                root = "bladehelp";
            }
            if (!HASH_COMMAND_ROOTS.contains(root)) {
                return true;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.player.networkHandler == null) {
                return false;
            }

            client.player.networkHandler.sendChatCommand(command);
            BladelowHudTelemetry.recordLocalMessage("/" + command);
            client.player.sendMessage(Text.literal("[Bladelow] ran: /" + command).formatted(Formatting.AQUA), false);
            return false;
        });
    }

    public static void onClientTick(MinecraftClient client) {
        if (openHudKey == null) {
            return;
        }

        while (openHudKey.wasPressed()) {
            if (client.currentScreen instanceof BladelowHudScreen) {
                client.setScreen(null);
            } else {
                client.setScreen(new BladelowHudScreen());
            }
        }
    }
}
