package com.bladelow.mixin.client;

import com.bladelow.client.BladelowClientMod;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void bladelow$onClientTick(CallbackInfo ci) {
        BladelowClientMod.onClientTick((MinecraftClient) (Object) this);
    }
}
