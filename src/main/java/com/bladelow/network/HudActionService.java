package com.bladelow.network;

import com.bladelow.BladelowMod;
import com.bladelow.auto.CityAutoplayDirector;
import com.bladelow.builder.BuildSiteAnalyzer;
import com.bladelow.builder.BuildSiteScan;
import com.bladelow.builder.BuildProfileStore;
import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.IntentStructurePlanner;
import com.bladelow.builder.SelectionState;
import com.bladelow.builder.TownAutoLayoutPlanner;
import com.bladelow.builder.TownDistrictType;
import com.bladelow.builder.TownPlanner;
import com.bladelow.builder.TownZoneStore;
import com.bladelow.command.MaterialResolver;
import com.bladelow.command.PaletteAssigner;
import com.bladelow.command.PlacementPipeline;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Direct HUD/server action dispatcher.
 *
 * The HUD emits typed {@link HudAction} values, and the server forwards each
 * action directly into the underlying Java services without any command-line
 * parsing layer in the middle.
 */
public final class HudActionService {
    private static final int MAX_SELECTION_BOX_BLOCKS = 131072;

    private HudActionService() {
    }

    public static boolean execute(ServerPlayerEntity player, HudCommandPayload payload) {
        if (player == null || payload == null) {
            return false;
        }
        String normalized = payload.describe();
        List<String> args = payload.args();
        ServerCommandSource source = player.getCommandSource();
        try {
            return switch (payload.action()) {
                case PAUSE_BUILD -> {
                    handlePause(source, player);
                    yield true;
                }
                case CONTINUE_BUILD -> {
                    handleContinue(source, player);
                    yield true;
                }
                case CANCEL_BUILD -> {
                    handleCancel(source, player);
                    yield true;
                }
                case STATUS -> {
                    handleStatus(source, player, false);
                    yield true;
                }
                case STATUS_DETAIL -> {
                    handleStatus(source, player, true);
                    yield true;
                }
                case SELECTION_CLEAR, SELECTION_MARKER_BOX, SELECTION_BUILD_HEIGHT ->
                    handleSelection(source, player, payload.action(), args);
                case ZONE_SET, ZONE_LIST, ZONE_CLEAR, ZONE_AUTO_LAYOUT ->
                    handleZone(source, player, payload.action(), args);
                case BLUEPRINT_LOAD, BLUEPRINT_BUILD, TOWN_FILL_SELECTION, TOWN_PREVIEW_SELECTION,
                     CITY_BUILD_AUTO, CITY_AUTOPLAY_START, CITY_AUTOPLAY_STATUS, CITY_AUTOPLAY_STOP,
                     CITY_AUTOPLAY_CONTINUE, CITY_AUTOPLAY_CANCEL ->
                    handleBlueprint(source, player, payload.action(), args);
                case MOVE_SMART_ENABLE, MOVE_SMART_DISABLE, MOVE_SET_MODE, MOVE_SET_REACH ->
                    handleMove(source, payload.action(), args);
                case SAFETY_SET_PREVIEW -> handleSafety(source, args);
                case PROFILE_LOAD -> handleProfile(source, player, args);
                case MODEL_SCAN_INTENT, MODEL_SAVE_STYLE_EXAMPLE -> handleModel(source, player, payload.action(), args);
            };
        } catch (IllegalArgumentException ex) {
            error(source, "[Bladelow] " + ex.getMessage());
            return true;
        } catch (Exception ex) {
            BladelowMod.LOGGER.error("HUD action failed for {}: {}", player.getName().getString(), normalized, ex);
            error(source, "[Bladelow] action failed: " + ex.getMessage());
            return true;
        }
    }

    private static boolean handleSelection(ServerCommandSource source, ServerPlayerEntity player, HudAction action, List<String> args) {
        return switch (action) {
            case SELECTION_CLEAR -> {
                SelectionState.clear(player.getUuid(), source.getWorld().getRegistryKey());
                feedback(source, "[Bladelow] selection cleared");
                yield true;
            }
            case SELECTION_MARKER_BOX -> {
                if (args.size() < 8) {
                    throw new IllegalArgumentException("markerbox needs from/to coords and height");
                }
                BlockPos from = parsePos(args, 0);
                BlockPos to = parsePos(args, 3);
                int height = parseInt(args.get(6), "marker height");
                boolean hollow = args.size() > 7 && "hollow".equalsIgnoreCase(args.get(7));
                applyMarkerBox(source, player, from, to, height, hollow);
                yield true;
            }
            case SELECTION_BUILD_HEIGHT -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException("buildh needs height and block ids");
                }
                int height = parseInt(args.get(0), "selection height");
                String blockSpec = remainder(args, 1);
                runSelectionBuildHeight(source, player, height, blockSpec);
                yield true;
            }
            default -> false;
        };
    }

    private static boolean handleZone(ServerCommandSource source, ServerPlayerEntity player, HudAction action, List<String> args) {
        return switch (action) {
            case ZONE_SET -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("district type required");
                }
                TownZoneStore.ZoneResult result = TownZoneStore.setSelection(
                    player.getUuid(),
                    source.getWorld().getRegistryKey(),
                    args.get(0),
                    SelectionState.snapshot(player.getUuid(), source.getWorld().getRegistryKey())
                );
                if (!result.ok()) {
                    error(source, "[Bladelow] " + result.message());
                } else {
                    feedback(source, "[Bladelow] " + result.message());
                }
                yield true;
            }
            case ZONE_LIST -> {
                List<TownZoneStore.Zone> zones = TownZoneStore.snapshot(player.getUuid(), source.getWorld().getRegistryKey());
                if (zones.isEmpty()) {
                    feedback(source, "[Bladelow] no saved zones");
                    yield true;
                }
                Map<String, Integer> counts = TownZoneStore.summarizeByType(zones);
                List<String> summary = counts.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList();
                feedback(source, "[Bladelow] zones " + String.join(", ", summary));
                int shown = Math.min(6, zones.size());
                for (int i = 0; i < shown; i++) {
                    TownZoneStore.Zone zone = zones.get(i);
                    feedback(source, "[Bladelow] zone#" + (i + 1) + " " + zone.summary());
                }
                if (zones.size() > shown) {
                    feedback(source, "[Bladelow] +" + (zones.size() - shown) + " more zones");
                }
                yield true;
            }
            case ZONE_CLEAR -> {
                if (!args.isEmpty()) {
                    String normalizedType = TownZoneStore.normalizeType(args.get(0));
                    if (normalizedType.isBlank()) {
                        error(source, "[Bladelow] district type must be " + TownDistrictType.idsCsv());
                        yield true;
                    }
                    int removed = TownZoneStore.clear(player.getUuid(), source.getWorld().getRegistryKey(), normalizedType);
                    feedback(source, "[Bladelow] cleared " + normalizedType + " zones=" + removed);
                    yield true;
                }
                int removed = TownZoneStore.clear(player.getUuid(), source.getWorld().getRegistryKey());
                feedback(source, "[Bladelow] cleared zones=" + removed);
                yield true;
            }
            case ZONE_AUTO_LAYOUT -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("autolayout needs a preset");
                }
                BlockPos[] bounds = selectionBounds2d(player, source);
                if (bounds == null) {
                    yield true;
                }
                boolean clearExisting = !(args.size() > 1 && "append".equalsIgnoreCase(args.get(1)));
                TownAutoLayoutPlanner.ApplyResult result = TownAutoLayoutPlanner.apply(
                    player.getUuid(),
                    source.getWorld().getRegistryKey(),
                    bounds[0],
                    bounds[1],
                    args.get(0),
                    clearExisting,
                    source.getWorld()
                );
                if (!result.ok()) {
                    error(source, "[Bladelow] " + result.message());
                } else {
                    feedback(source, "[Bladelow] " + result.message());
                }
                yield true;
            }
            default -> false;
        };
    }

    private static boolean handleBlueprint(ServerCommandSource source, ServerPlayerEntity player, HudAction action, List<String> args) {
        return switch (action) {
            case BLUEPRINT_LOAD -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("blueprint name required");
                }
                boolean ok = BlueprintLibrary.select(player.getUuid(), args.get(0));
                if (!ok) {
                    error(source, "[Bladelow] unknown blueprint: " + args.get(0));
                } else {
                    feedback(source, "[Bladelow] selected blueprint " + args.get(0));
                }
                yield true;
            }
            case BLUEPRINT_BUILD -> {
                runBlueprintBuild(source, player, args);
                yield true;
            }
            case TOWN_FILL_SELECTION -> {
                BlockPos[] bounds = selectionBounds3d(player, source);
                if (bounds == null) {
                    yield true;
                }
                BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveTownFill(source.getWorld(), player.getUuid(), bounds[0], bounds[1]);
                if (!plan.ok()) {
                    error(source, "[Bladelow] " + plan.message());
                } else {
                    feedback(source, "[Bladelow] townfill area from=" + bounds[0].toShortString() + " to=" + bounds[1].toShortString());
                    PlacementPipeline.queue(source, player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message(), false);
                }
                yield true;
            }
            case TOWN_PREVIEW_SELECTION -> {
                BlockPos[] bounds = selectionBounds3d(player, source);
                if (bounds == null) {
                    yield true;
                }
                BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveTownFill(source.getWorld(), player.getUuid(), bounds[0], bounds[1], "", false);
                if (!plan.ok()) {
                    error(source, "[Bladelow] " + plan.message());
                } else {
                    feedback(source, "[Bladelow] townpreview area from=" + bounds[0].toShortString() + " to=" + bounds[1].toShortString());
                    PlacementPipeline.queue(source, player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message(), true);
                }
                yield true;
            }
            case CITY_BUILD_AUTO -> {
                BlockPos[] bounds = selectionBounds3d(player, source);
                if (bounds == null) {
                    yield true;
                }
                String plotLabel = args.isEmpty() ? "" : args.get(0);
                List<String> slotOverrides = new ArrayList<>();
                for (int i = 1; i < Math.min(args.size(), 4); i++) {
                    String token = args.get(i);
                    if (!token.equals("-")) {
                        slotOverrides.add(token);
                    }
                }
                IntentStructurePlanner.GeneratedBuild plan = IntentStructurePlanner.planSelection(
                    source.getWorld(),
                    player.getUuid(),
                    bounds[0],
                    bounds[1],
                    plotLabel,
                    slotOverrides
                );
                if (!plan.ok()) {
                    error(source, "[Bladelow] " + plan.message());
                } else {
                    feedback(source, "[Bladelow] " + plan.message());
                    PlacementPipeline.queue(source, player, plan.blockStates(), plan.targets(), "autobuild:" + plan.blueprint().name(), false);
                }
                yield true;
            }
            case CITY_AUTOPLAY_START -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("cityautoplay needs a preset");
                }
                BlockPos[] bounds = selectionBounds3d(player, source);
                if (bounds == null) {
                    yield true;
                }
                boolean clearExisting = !(args.size() > 1 && "append".equalsIgnoreCase(args.get(1)));
                CityAutoplayDirector.StartResult result = CityAutoplayDirector.start(
                    source,
                    player,
                    bounds[0],
                    bounds[1],
                    args.get(0),
                    clearExisting
                );
                if (!result.ok()) {
                    error(source, "[Bladelow] " + result.message());
                } else {
                    feedback(source, "[Bladelow] " + result.message());
                }
                yield true;
            }
            case CITY_AUTOPLAY_STATUS -> {
                feedback(source, "[Bladelow] city " + CityAutoplayDirector.status(player.getUuid()));
                yield true;
            }
            case CITY_AUTOPLAY_STOP -> {
                feedback(source, "[Bladelow] " + CityAutoplayDirector.stop(source.getServer(), player.getUuid()));
                yield true;
            }
            case CITY_AUTOPLAY_CONTINUE -> {
                feedback(source, "[Bladelow] " + CityAutoplayDirector.resume(source.getServer(), player.getUuid()));
                yield true;
            }
            case CITY_AUTOPLAY_CANCEL -> {
                feedback(source, "[Bladelow] " + CityAutoplayDirector.cancel(source.getServer(), player.getUuid()));
                yield true;
            }
            default -> false;
        };
    }

    private static boolean handleMove(ServerCommandSource source, HudAction action, List<String> args) {
        return switch (action) {
            case MOVE_SMART_ENABLE -> {
                BuildRuntimeSettings.setSmartMoveEnabled(true);
                feedback(source, "[Bladelow] smart move enabled");
                yield true;
            }
            case MOVE_SMART_DISABLE -> {
                BuildRuntimeSettings.setSmartMoveEnabled(false);
                feedback(source, "[Bladelow] smart move disabled");
                yield true;
            }
            case MOVE_SET_MODE -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("move mode required");
                }
                if (!BuildRuntimeSettings.setMoveMode(args.get(0))) {
                    error(source, "[Bladelow] mode must be walk, auto, or teleport");
                } else {
                    feedback(source, "[Bladelow] mode set to " + args.get(0));
                }
                yield true;
            }
            case MOVE_SET_REACH -> {
                if (args.isEmpty()) {
                    throw new IllegalArgumentException("reach distance required");
                }
                double reach = parseDouble(args.get(0), "reach distance");
                BuildRuntimeSettings.setReachDistance(reach);
                feedback(source, "[Bladelow] reach distance set to " + String.format(Locale.ROOT, "%.2f", BuildRuntimeSettings.reachDistance()));
                yield true;
            }
            default -> false;
        };
    }

    private static boolean handleSafety(ServerCommandSource source, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("use on|off");
        }
        Boolean enabled = parseOnOff(args.get(0));
        if (enabled == null) {
            throw new IllegalArgumentException("use on|off");
        }
        BuildRuntimeSettings.setPreviewBeforeBuild(enabled);
        feedback(source, "[Bladelow] preview-before-build set to " + (enabled ? "on" : "off"));
        return true;
    }

    private static boolean handleProfile(ServerCommandSource source, ServerPlayerEntity player, List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("profile name required");
        }
        String status = BuildProfileStore.load(source.getServer(), args.get(0));
        feedback(source, "[Bladelow] " + status + " => " + BuildRuntimeSettings.summary());
        return true;
    }

    private static boolean handleModel(ServerCommandSource source, ServerPlayerEntity player, HudAction action, List<String> args) {
        return switch (action) {
            case MODEL_SCAN_INTENT -> {
                BlockPos[] bounds = selectionBounds3d(player, source);
                if (bounds == null) {
                    yield true;
                }
                TownPlanner.IntentSuggestion suggestion = TownPlanner.suggestBuildIntent(
                    source.getWorld(),
                    bounds[0],
                    bounds[1],
                    TownZoneStore.snapshot(player.getUuid(), source.getWorld().getRegistryKey())
                );
                if (!suggestion.ok()) {
                    error(source, "[Bladelow] " + suggestion.message());
                } else {
                    feedback(source, "[Bladelow] " + suggestion.message());
                }
                yield true;
            }
            case MODEL_SAVE_STYLE_EXAMPLE -> {
                BlockPos[] bounds = selectionBounds3d(player, source);
                if (bounds == null) {
                    yield true;
                }
                BuildSiteScan scan = BuildSiteAnalyzer.scan(source.getWorld(), bounds[0], bounds[1], bounds[0].getY(), Set.of());
                if (scan == BuildSiteScan.EMPTY) {
                    error(source, "[Bladelow] could not extract a style scan from the selected area");
                    yield true;
                }
                String label = remainder(args, 0);
                if (label.isBlank()) {
                    label = "example";
                }
                String normalizedLabel = label.trim().toLowerCase(Locale.ROOT);
                com.bladelow.ml.BladelowLearning.styleExampleLogger().recordExample(
                    "hud",
                    normalizedLabel,
                    source.getWorld(),
                    bounds[0],
                    bounds[1],
                    scan
                );
                com.bladelow.ml.BladelowLearning.environmentLogger().recordScan(
                    "style_example:" + normalizedLabel,
                    source.getWorld(),
                    bounds[0],
                    bounds[1],
                    scan
                );
                feedback(source, "[Bladelow] saved style example " + normalizedLabel + " => " + scan.summary());
                yield true;
            }
            default -> false;
        };
    }

    private static void runBlueprintBuild(ServerCommandSource source, ServerPlayerEntity player, List<String> args) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("blueprint build needs coords");
        }
        int index = 0;
        String name = null;
        if (!isInteger(args.get(index))) {
            if (args.size() < 4) {
                throw new IllegalArgumentException("blueprint build needs name and coords");
            }
            name = args.get(index++);
        }
        BlockPos start = parsePos(args, index);
        index += 3;
        String blockSpec = index < args.size() ? remainder(args, index) : null;

        BlueprintLibrary.BuildPlan plan = name == null
            ? BlueprintLibrary.resolveSelected(player.getUuid(), start)
            : BlueprintLibrary.resolveByName(name, start);
        if (!plan.ok()) {
            error(source, "[Bladelow] " + plan.message());
            return;
        }
        if (name != null) {
            BlueprintLibrary.select(player.getUuid(), name);
        }

        if (blockSpec == null || blockSpec.isBlank()) {
            PlacementPipeline.queue(source, player, plan.blockStates(), plan.targets(), "blueprint:" + plan.message(), false);
            return;
        }

        List<Block> override = MaterialResolver.parseBlockSpec(blockSpec, source);
        if (override.isEmpty()) {
            return;
        }
        List<Block> blocks = PaletteAssigner.applyOverride(plan.blocks(), plan.targets(), override);
        PlacementPipeline.queue(source, player, PaletteAssigner.defaultStates(blocks), plan.targets(), "blueprint:" + plan.message(), false);
    }

    private static void runSelectionBuildHeight(ServerCommandSource source, ServerPlayerEntity player, int height, String blockSpec) {
        if (height < 1 || height > 256) {
            throw new IllegalArgumentException("height must be 1..256");
        }
        List<Block> blocks = MaterialResolver.parseBlockSpec(blockSpec, source);
        if (blocks.isEmpty()) {
            return;
        }
        List<BlockPos> base = SelectionState.snapshot(player.getUuid(), source.getWorld().getRegistryKey());
        if (base.isEmpty()) {
            error(source, "[Bladelow] selection is empty; mark an area in the HUD first");
            return;
        }
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : base) {
            for (int step = 1; step <= height; step++) {
                targets.add(pos.add(0, step, 0));
            }
        }
        if (targets.isEmpty()) {
            error(source, "[Bladelow] no targets for selection height");
            return;
        }
        PlacementPipeline.run(source, player, blocks, targets, "selectionh");
    }

    private static void applyMarkerBox(ServerCommandSource source, ServerPlayerEntity player, BlockPos from, BlockPos to, int height, boolean hollow) {
        if (height < 1 || height > 256) {
            throw new IllegalArgumentException("height must be 1..256");
        }
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());
        int baseY = Math.min(from.getY(), to.getY());

        long area = ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
        if (area > MAX_SELECTION_BOX_BLOCKS) {
            error(source, "[Bladelow] marker box too large (" + area + " base blocks). limit=" + MAX_SELECTION_BOX_BLOCKS);
            return;
        }

        SelectionState.clear(player.getUuid(), source.getWorld().getRegistryKey());
        int added = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean boundary = x == minX || x == maxX || z == minZ || z == maxZ;
                if (hollow && !boundary) {
                    continue;
                }
                if (SelectionState.add(player.getUuid(), source.getWorld().getRegistryKey(), new BlockPos(x, baseY, z))) {
                    added++;
                }
            }
        }

        showAreaMarkers(source, minX, maxX, minZ, maxZ, baseY, height);
        int total = SelectionState.size(player.getUuid(), source.getWorld().getRegistryKey());
        String mode = hollow ? "hollow" : "solid";
        feedback(source,
            "[Bladelow] marker box set " + mode
                + " L=" + (maxX - minX + 1)
                + " W=" + (maxZ - minZ + 1)
                + " H=" + height
                + " baseY=" + baseY
                + " topY=" + (baseY + height)
                + " selected=" + total
                + " (white=length blue=width red=height)"
        );
        if (added <= 0) {
            error(source, "[Bladelow] marker box did not change the selection");
        }
    }

    private static void showAreaMarkers(ServerCommandSource source, int minX, int maxX, int minZ, int maxZ, int baseY, int height) {
        ServerWorld world = source.getWorld();
        double y = baseY + 1.05;
        for (int x = minX; x <= maxX; x++) {
            world.spawnParticles(ParticleTypes.END_ROD, x + 0.5, y, minZ + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(ParticleTypes.END_ROD, x + 0.5, y, maxZ + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
        }
        for (int z = minZ; z <= maxZ; z++) {
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, minX + 0.5, y, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, maxX + 0.5, y, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
        }
        int[][] corners = new int[][]{{minX, minZ}, {minX, maxZ}, {maxX, minZ}, {maxX, maxZ}};
        for (int[] corner : corners) {
            for (int step = 1; step <= height; step++) {
                world.spawnParticles(ParticleTypes.FLAME, corner[0] + 0.5, baseY + step + 0.05, corner[1] + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private static void handlePause(ServerCommandSource source, ServerPlayerEntity player) {
        boolean paused = com.bladelow.builder.PlacementJobRunner.pause(source.getServer(), player.getUuid());
        if (paused) {
            feedback(source, "[Bladelow] build paused. Use the HUD or #bladecontinue to resume.");
            return;
        }
        if (com.bladelow.builder.PlacementJobRunner.hasPending(player.getUuid())) {
            feedback(source, "[Bladelow] build is already paused/pending.");
            return;
        }
        feedback(source, "[Bladelow] no active build to pause.");
    }

    private static void handleContinue(ServerCommandSource source, ServerPlayerEntity player) {
        if (com.bladelow.builder.PlacementJobRunner.hasActive(player.getUuid())) {
            feedback(source, "[Bladelow] build is already running.");
            return;
        }
        boolean resumed = com.bladelow.builder.PlacementJobRunner.resume(source.getServer(), player.getUuid());
        if (!resumed) {
            error(source, "[Bladelow] no paused/pending build to continue.");
            return;
        }
        feedback(source, "[Bladelow] build continued.");
    }

    private static void handleCancel(ServerCommandSource source, ServerPlayerEntity player) {
        boolean canceled = com.bladelow.builder.PlacementJobRunner.cancel(source.getServer(), player.getUuid());
        feedback(source, canceled
            ? "[Bladelow] active build canceled."
            : "[Bladelow] no active build to cancel.");
    }

    private static void handleStatus(ServerCommandSource source, ServerPlayerEntity player, boolean detail) {
        String msg = detail
            ? com.bladelow.builder.PlacementJobRunner.statusDetail(player.getUuid())
            : com.bladelow.builder.PlacementJobRunner.status(player.getUuid());
        feedback(source, "[Bladelow] " + msg);
    }

    private static BlockPos[] selectionBounds3d(ServerPlayerEntity player, ServerCommandSource source) {
        List<BlockPos> points = SelectionState.snapshot(player.getUuid(), source.getWorld().getRegistryKey());
        if (points.isEmpty()) {
            error(source, "[Bladelow] selection is empty; mark an area in the HUD first");
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos point : points) {
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
            maxZ = Math.max(maxZ, point.getZ());
        }
        return new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }

    private static BlockPos[] selectionBounds2d(ServerPlayerEntity player, ServerCommandSource source) {
        List<BlockPos> points = SelectionState.snapshot(player.getUuid(), source.getWorld().getRegistryKey());
        if (points.isEmpty()) {
            error(source, "[Bladelow] selection is empty; mark an area in the HUD first");
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos point : points) {
            minX = Math.min(minX, point.getX());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxZ = Math.max(maxZ, point.getZ());
        }
        return new BlockPos[]{new BlockPos(minX, 0, minZ), new BlockPos(maxX, 0, maxZ)};
    }

    private static String remainder(List<String> tokens, int startIndex) {
        if (startIndex >= tokens.size()) {
            return "";
        }
        return String.join(" ", tokens.subList(startIndex, tokens.size())).trim();
    }

    private static BlockPos parsePos(List<String> tokens, int startIndex) {
        if (tokens.size() <= startIndex + 2) {
            throw new IllegalArgumentException("invalid block position");
        }
        return new BlockPos(
            parseInt(tokens.get(startIndex), "x"),
            parseInt(tokens.get(startIndex + 1), "y"),
            parseInt(tokens.get(startIndex + 2), "z")
        );
    }

    private static int parseInt(String text, String label) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid " + label + ": " + text);
        }
    }

    private static double parseDouble(String text, String label) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid " + label + ": " + text);
        }
    }

    private static Boolean parseOnOff(String text) {
        if ("on".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("off".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isInteger(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int start = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        if (start >= text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void feedback(ServerCommandSource source, String message) {
        source.sendFeedback(() -> blue(message), false);
    }

    private static void error(ServerCommandSource source, String message) {
        source.sendError(blue(message));
    }

    private static Text blue(String msg) {
        return Text.literal(msg).formatted(Formatting.AQUA);
    }
}
