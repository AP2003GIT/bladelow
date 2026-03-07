package com.bladelow.command;

import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.BuildItWebService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Handles all #bladeweb commands: catalog, import, importnamed, importload, importloadurl.
 * All HTTP calls are async — they never block the server tick thread.
 * Extracted from BladePlaceCommand.
 */
public final class WebCommands {

    private WebCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("bladeweb")
            .then(literal("catalog")
                .executes(ctx -> runCatalog(ctx.getSource(), 12))
                .then(argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(ctx -> runCatalog(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "limit")))
                )
            )
            .then(literal("import")
                .then(argument("index", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> runImportByIndex(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"), ""))
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> runImportByIndex(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "index"),
                            StringArgumentType.getString(ctx, "name")))
                    )
                )
                .then(argument("url", StringArgumentType.greedyString())
                    .executes(ctx -> runImportUrl(ctx.getSource(), StringArgumentType.getString(ctx, "url"), ""))
                )
            )
            .then(literal("importnamed")
                .then(argument("name", StringArgumentType.word())
                    .then(argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> runImportUrl(ctx.getSource(),
                            StringArgumentType.getString(ctx, "url"),
                            StringArgumentType.getString(ctx, "name")))
                    )
                )
            )
            .then(literal("importload")
                .then(argument("index", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> runImportLoad(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index"), ""))
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> runImportLoad(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "index"),
                            StringArgumentType.getString(ctx, "name")))
                    )
                )
            )
            .then(literal("importloadurl")
                .then(argument("name", StringArgumentType.word())
                    .then(argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> runImportLoadUrl(ctx.getSource(),
                            StringArgumentType.getString(ctx, "url"),
                            StringArgumentType.getString(ctx, "name")))
                    )
                )
            )
        );
    }

    // -------------------------------------------------------------------------
    // Runners — all async
    // -------------------------------------------------------------------------

    private static int runCatalog(ServerCommandSource source, int limit) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        UUID playerId = player.getUuid();
        source.sendFeedback(() -> blue("[Bladelow] Syncing catalog..."), false);
        BuildItWebService.syncCatalogAsync(source.getServer(), playerId, limit, res -> {
            if (!res.ok()) { source.sendError(blue("[Bladelow] " + res.message())); return; }
            source.sendFeedback(() -> blue("[Bladelow] " + res.message()), false);
            List<BuildItWebService.CatalogItem> list = BuildItWebService.catalog(playerId);
            for (BuildItWebService.CatalogItem item : list) {
                source.sendFeedback(() -> blue("[" + item.index() + "] " + item.title()), false);
            }
        });
        return 1;
    }

    private static int runImportByIndex(ServerCommandSource source, int index, String name) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        source.sendFeedback(() -> blue("[Bladelow] Importing..."), false);
        BuildItWebService.importPickedAsync(source.getServer(), player.getUuid(), index, name, res -> {
            if (!res.ok()) source.sendError(blue("[Bladelow] " + res.message()));
            else source.sendFeedback(() -> blue("[Bladelow] " + res.message()), false);
        });
        return 1;
    }

    private static int runImportUrl(ServerCommandSource source, String url, String name) {
        source.sendFeedback(() -> blue("[Bladelow] Importing from URL..."), false);
        BuildItWebService.importFromUrlAsync(source.getServer(), url, name, res -> {
            if (!res.ok()) source.sendError(blue("[Bladelow] " + res.message()));
            else source.sendFeedback(() -> blue("[Bladelow] " + res.message()), false);
        });
        return 1;
    }

    private static int runImportLoad(ServerCommandSource source, int index, String requestedName) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        String finalName = (requestedName == null || requestedName.isBlank())
            ? "web_idx_" + index : requestedName;
        UUID playerId = player.getUuid();
        MinecraftServer server = source.getServer();
        source.sendFeedback(() -> blue("[Bladelow] Importing blueprint..."), false);
        BuildItWebService.importPickedAsync(server, playerId, index, finalName, res -> {
            if (!res.ok()) { source.sendError(blue("[Bladelow] " + res.message())); return; }
            source.sendFeedback(() -> blue("[Bladelow] " + res.message()), false);
            boolean selected = BlueprintLibrary.select(playerId, finalName);
            if (selected) source.sendFeedback(() -> blue("[Bladelow] selected blueprint " + finalName), false);
            else source.sendError(blue("[Bladelow] imported but failed to load blueprint " + finalName));
        });
        return 1;
    }

    private static int runImportLoadUrl(ServerCommandSource source, String url, String name) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(blue("Player context required.")); return 0; }
        UUID playerId = player.getUuid();
        MinecraftServer server = source.getServer();
        source.sendFeedback(() -> blue("[Bladelow] Importing from URL..."), false);
        BuildItWebService.importFromUrlAsync(server, url, name, res -> {
            if (!res.ok()) { source.sendError(blue("[Bladelow] " + res.message())); return; }
            source.sendFeedback(() -> blue("[Bladelow] " + res.message()), false);
            boolean selected = BlueprintLibrary.select(playerId, name);
            if (selected) source.sendFeedback(() -> blue("[Bladelow] selected blueprint " + name), false);
            else source.sendError(blue("[Bladelow] imported but failed to load blueprint " + name));
        });
        return 1;
    }

    static Text blue(String msg) {
        return Text.literal(msg).formatted(Formatting.AQUA);
    }
}
