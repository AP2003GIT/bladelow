package com.bladelow.client;

import com.bladelow.client.ui.BladelowHudScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public class BladelowClientMod implements ClientModInitializer {
    private static final String KEY_OPEN_HUD = "key.bladelow.open_hud";

    private static KeyBinding openHudKey;

    @Override
    public void onInitializeClient() {
        openHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            KEY_OPEN_HUD,
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            KeyBinding.Category.MISC
        ));

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message == null ? "" : message.trim();
            if (!trimmed.startsWith("#")) {
                return true;
            }

            String command = trimmed.substring(1).trim();
            if (command.isEmpty()) {
                return true;
            }

            String lower = command.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("blade") && !lower.startsWith("bladelow")) {
                return true;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.player.networkHandler == null) {
                return false;
            }

            client.player.networkHandler.sendChatCommand(command);
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
