package com.bladelow.command;

import com.bladelow.network.HudAction;
import com.bladelow.network.HudActionService;
import com.bladelow.network.HudCommandPayload;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Tiny manual recovery command surface kept around as a safety valve.
 *
 * Normal usage should stay HUD-first. These commands exist only so the player
 * can pause, resume, cancel, or inspect a build if something goes wrong.
 */
public final class ManualRecoveryCommands {
    private ManualRecoveryCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladepause").executes(ctx -> run(ctx.getSource(), HudAction.PAUSE_BUILD)));
        dispatcher.register(literal("bladecontinue").executes(ctx -> run(ctx.getSource(), HudAction.CONTINUE_BUILD)));
        dispatcher.register(literal("bladecancel").executes(ctx -> run(ctx.getSource(), HudAction.CANCEL_BUILD)));
        dispatcher.register(literal("bladestatus")
            .executes(ctx -> run(ctx.getSource(), HudAction.STATUS))
            .then(literal("detail").executes(ctx -> run(ctx.getSource(), HudAction.STATUS_DETAIL)))
        );
    }

    private static int run(ServerCommandSource source, HudAction action) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Player context required.").formatted(Formatting.AQUA));
            return 0;
        }
        return HudActionService.execute(player, HudCommandPayload.of(action)) ? 1 : 0;
    }
}
