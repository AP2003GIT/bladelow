package com.bladelow.network;

import com.bladelow.BladelowMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal client->server payload for HUD actions.
 *
 * The payload now carries a single typed action enum plus a normalized string
 * argument list for action-specific parameters.
 */
public record HudCommandPayload(HudAction action, List<String> args) implements CustomPayload {
    public static final CustomPayload.Id<HudCommandPayload> ID =
        new CustomPayload.Id<>(Identifier.of(BladelowMod.MOD_ID, "hud_action"));
    private static final PacketCodec<ByteBuf, HudAction> ACTION_CODEC =
        PacketCodecs.INTEGER.xmap(HudAction::fromOrdinal, HudAction::ordinal);
    public static final PacketCodec<ByteBuf, HudCommandPayload> CODEC = PacketCodec.tuple(
        ACTION_CODEC,
        HudCommandPayload::action,
        PacketCodecs.collection(ArrayList::new, PacketCodecs.STRING),
        HudCommandPayload::args,
        HudCommandPayload::new
    );

    public HudCommandPayload {
        if (action == null) {
            throw new IllegalArgumentException("HUD action is required");
        }
        args = normalizeArgs(args);
    }

    public static HudCommandPayload of(HudAction action, String... args) {
        return new HudCommandPayload(action, args == null ? List.of() : List.of(args));
    }

    public String describe() {
        if (args.isEmpty()) {
            return action.wireId();
        }
        return action.wireId() + " " + String.join(" ", args);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static List<String> normalizeArgs(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }
}
