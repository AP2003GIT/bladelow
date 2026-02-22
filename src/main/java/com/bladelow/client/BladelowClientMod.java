package com.bladelow.client;

import com.bladelow.client.ui.BladelowHudScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

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
