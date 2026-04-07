package com.bladelow.auto;

import com.bladelow.builder.BuildRuntimeSettings;
import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.PlacementJob;
import com.bladelow.builder.PlacementJobRunner;
import com.bladelow.builder.TownAutoLayoutPlanner;
import com.bladelow.builder.TownDistrictType;
import com.bladelow.builder.TownZoneStore;
import com.bladelow.command.MaterialResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * City director mode for full autopilot:
 *   1) one-command start
 *   2) runtime recovery relies on PlacementJobRunner task-node recover loop
 *   3) terrain prep stage per district
 *   4) material auto-resolution before queue
 *   5) district queue scheduler
 *   6) session persistence (resume after restart)
 *   7) HUD-friendly status/control commands
 */
public final class CityAutoplayDirector {
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final List<String> BASE_DISTRICT_ORDER = List.of(
        TownDistrictType.CIVIC.id(),
        TownDistrictType.MARKET.id(),
        TownDistrictType.WORKSHOP.id(),
        TownDistrictType.RESIDENTIAL.id(),
        TownDistrictType.MIXED.id()
    );
    private static final Path SESSION_PATH = Path.of("config", "bladelow", "city-director.properties");

    private static volatile boolean dirty;
    private static volatile String lastSaveError = "";

    private CityAutoplayDirector() {
    }

    public record StartResult(boolean ok, String message) {
        static StartResult ok(String message) {
            return new StartResult(true, message);
        }

        static StartResult error(String message) {
            return new StartResult(false, message);
        }
    }

    private enum Stage {
        IDLE,
        PREP_RUNNING,
        BUILD_RUNNING,
        PAUSED
    }

    private static final class Session {
        private final UUID playerId;
        private final String worldId;
        private final BlockPos from;
        private final BlockPos to;
        private final String preset;
        private final List<String> districtOrder;
        private int nextDistrictIndex;
        private boolean roadsIncluded;
        private Stage stage;
        private String activeDistrict;
        private String awaitingTag;
        private String lastSummary;
        private int queuedDistricts;
        private int builtDistricts;
        private int skippedDistricts;
        private int failedDistricts;
        private final long startedAtEpochMs;
        private long updatedAtEpochMs;
        private List<BlockState> pendingStates;
        private List<BlockPos> pendingTargets;

        private Session(
            UUID playerId,
            String worldId,
            BlockPos from,
            BlockPos to,
            String preset,
            List<String> districtOrder
        ) {
            this.playerId = playerId;
            this.worldId = worldId;
            this.from = from;
            this.to = to;
            this.preset = preset;
            this.districtOrder = List.copyOf(districtOrder);
            this.stage = Stage.IDLE;
            this.activeDistrict = "";
            this.awaitingTag = "";
            this.lastSummary = "ready";
            this.startedAtEpochMs = System.currentTimeMillis();
            this.updatedAtEpochMs = this.startedAtEpochMs;
            this.pendingStates = List.of();
            this.pendingTargets = List.of();
        }

        private void touch(String summary) {
            if (summary != null && !summary.isBlank()) {
                this.lastSummary = summary;
            }
            this.updatedAtEpochMs = System.currentTimeMillis();
            dirty = true;
        }
    }

    public static StartResult start(
        ServerCommandSource source,
        ServerPlayerEntity player,
        BlockPos from,
        BlockPos to,
        String rawPreset,
        boolean clearExistingZones
    ) {
        if (source == null || player == null || from == null || to == null) {
            return StartResult.error("invalid city autoplay arguments");
        }
        if (PlacementJobRunner.hasActive(player.getUuid()) || PlacementJobRunner.hasPending(player.getUuid())) {
            return StartResult.error("another build is active; stop/cancel it first");
        }
        if (SESSIONS.containsKey(player.getUuid())) {
            return StartResult.error("city autoplay already active; use the HUD controls or /bladeblueprint citystop or citycancel");
        }
        String preset = TownAutoLayoutPlanner.normalizePreset(rawPreset);
        if (preset.isBlank()) {
            return StartResult.error("preset must be balanced|medieval|harbor|adaptive");
        }

        TownAutoLayoutPlanner.ApplyResult zoning = TownAutoLayoutPlanner.apply(
            player.getUuid(),
            source.getWorld().getRegistryKey(),
            from,
            to,
            preset,
            clearExistingZones,
            source.getWorld()
        );
        if (!zoning.ok()) {
            return StartResult.error(zoning.message());
        }

        List<String> order = scheduleDistrictOrder(
            TownZoneStore.snapshot(player.getUuid(), source.getWorld().getRegistryKey()),
            from,
            to
        );
        Session session = new Session(
            player.getUuid(),
            source.getWorld().getRegistryKey().getValue().toString(),
            from,
            to,
            preset,
            order
        );
        session.touch("autoplay started");
        SESSIONS.put(player.getUuid(), session);
        save(source.getServer());
        return StartResult.ok(
            "city autoplay started preset=" + preset
                + " order=" + String.join(">", order)
                + " zones=" + zoning.saved()
        );
    }

    public static String status(UUID playerId) {
        Session session = SESSIONS.get(playerId);
        if (session == null) {
            return "no active city autoplay session";
        }
        String current = session.activeDistrict == null || session.activeDistrict.isBlank()
            ? "-"
            : session.activeDistrict;
        String stage = session.stage.name().toLowerCase(Locale.ROOT);
        String runtimeAge = formatAgeMillis(Math.max(0L, System.currentTimeMillis() - session.startedAtEpochMs));
        return "preset=" + session.preset
            + " stage=" + stage
            + " district=" + current
            + " progress=" + session.builtDistricts + "/" + session.districtOrder.size()
            + " next=" + Math.min(session.nextDistrictIndex + 1, session.districtOrder.size()) + "/" + session.districtOrder.size()
            + " skipped=" + session.skippedDistricts
            + " failed=" + session.failedDistricts
            + " age=" + runtimeAge
            + " last=\"" + session.lastSummary + "\""
            + (lastSaveError.isBlank() ? "" : " saveError=\"" + lastSaveError + "\"");
    }

    public static String stop(MinecraftServer server, UUID playerId) {
        Session session = SESSIONS.get(playerId);
        if (session == null) {
            return "no active city autoplay session";
        }
        if (session.stage == Stage.PAUSED) {
            return "city autoplay already paused";
        }
        session.stage = Stage.PAUSED;
        session.touch("paused");
        PlacementJobRunner.pause(server, playerId);
        return "city autoplay paused";
    }

    public static String resume(MinecraftServer server, UUID playerId) {
        Session session = SESSIONS.get(playerId);
        if (session == null) {
            return "no active city autoplay session";
        }
        if (session.stage != Stage.PAUSED) {
            return "city autoplay is already running";
        }
        session.stage = Stage.IDLE;
        session.touch("resumed");
        PlacementJobRunner.resume(server, playerId);
        return "city autoplay resumed";
    }

    public static String cancel(MinecraftServer server, UUID playerId) {
        Session removed = SESSIONS.remove(playerId);
        PlacementJobRunner.cancel(server, playerId);
        dirty = true;
        if (removed == null) {
            return "no active city autoplay session";
        }
        save(server);
        return "city autoplay canceled";
    }

    public static boolean hasSession(UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }

    public static void tick(MinecraftServer server) {
        if (server == null || SESSIONS.isEmpty()) {
            return;
        }
        List<UUID> toRemove = new ArrayList<>();

        for (Session session : SESSIONS.values()) {
            if (session == null) {
                continue;
            }
            if (session.stage == Stage.PAUSED) {
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
            if (player == null) {
                continue;
            }
            if (!(player.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            if (!world.getRegistryKey().getValue().toString().equals(session.worldId)) {
                continue;
            }

            boolean busy = PlacementJobRunner.hasActive(session.playerId)
                || PlacementJobRunner.hasPending(session.playerId);
            if (busy) {
                continue;
            }

            if (session.stage == Stage.PREP_RUNNING) {
                if (!queueDistrictBuild(server, player, world, session)) {
                    session.failedDistricts++;
                    session.stage = Stage.PAUSED;
                    session.touch("failed to queue district build");
                    player.sendMessage(blueText("[Bladelow] city autoplay paused: failed to queue district build"), false);
                }
                continue;
            }

            if (session.stage == Stage.BUILD_RUNNING) {
                session.builtDistricts++;
                session.nextDistrictIndex++;
                session.roadsIncluded = true;
                session.activeDistrict = "";
                session.awaitingTag = "";
                session.pendingStates = List.of();
                session.pendingTargets = List.of();
                session.stage = Stage.IDLE;
                session.touch("district complete");
                player.sendMessage(blueText(
                    "[Bladelow] city autoplay district complete (" + session.builtDistricts + "/" + session.districtOrder.size() + ")"
                ), false);
                continue;
            }

            if (session.nextDistrictIndex >= session.districtOrder.size()) {
                toRemove.add(session.playerId);
                player.sendMessage(blueText(
                    "[Bladelow] city autoplay complete built=" + session.builtDistricts
                        + " skipped=" + session.skippedDistricts
                        + " failed=" + session.failedDistricts
                ), false);
                continue;
            }

            queueNextDistrict(server, player, world, session);
        }

        if (!toRemove.isEmpty()) {
            for (UUID playerId : toRemove) {
                SESSIONS.remove(playerId);
            }
            dirty = true;
        }

        if (dirty && (server.getTicks() % 20 == 0)) {
            save(server);
        }
    }

    public static int restore(MinecraftServer server) {
        SESSIONS.clear();
        Path path = server.getRunDirectory().resolve(SESSION_PATH);
        if (!Files.exists(path)) {
            return 0;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException ex) {
            return 0;
        }

        int count = parseInt(props.getProperty("count"), 0);
        int restored = 0;
        for (int i = 0; i < count; i++) {
            String prefix = "session." + i + ".";
            UUID playerId = parseUuid(props.getProperty(prefix + "player"));
            String worldId = props.getProperty(prefix + "world", "");
            BlockPos from = parsePos(props.getProperty(prefix + "from"));
            BlockPos to = parsePos(props.getProperty(prefix + "to"));
            String preset = TownAutoLayoutPlanner.normalizePreset(props.getProperty(prefix + "preset", "balanced"));
            String orderRaw = props.getProperty(prefix + "order", "");
            List<String> order = decodeOrder(orderRaw);
            if (playerId == null || worldId.isBlank() || from == null || to == null || order.isEmpty()) {
                continue;
            }
            Session session = new Session(playerId, worldId, from, to, preset.isBlank() ? "balanced" : preset, order);
            session.nextDistrictIndex = clamp(parseInt(props.getProperty(prefix + "next"), 0), 0, order.size());
            session.roadsIncluded = Boolean.parseBoolean(props.getProperty(prefix + "roadsIncluded", "false"));
            session.stage = parseStage(props.getProperty(prefix + "stage"));
            session.activeDistrict = props.getProperty(prefix + "activeDistrict", "");
            session.awaitingTag = props.getProperty(prefix + "awaitingTag", "");
            session.lastSummary = props.getProperty(prefix + "lastSummary", "restored");
            session.queuedDistricts = parseInt(props.getProperty(prefix + "queuedDistricts"), 0);
            session.builtDistricts = parseInt(props.getProperty(prefix + "builtDistricts"), 0);
            session.skippedDistricts = parseInt(props.getProperty(prefix + "skippedDistricts"), 0);
            session.failedDistricts = parseInt(props.getProperty(prefix + "failedDistricts"), 0);
            session.updatedAtEpochMs = parseLong(props.getProperty(prefix + "updatedAt"), System.currentTimeMillis());
            SESSIONS.put(playerId, session);
            restored++;
        }
        dirty = false;
        return restored;
    }

    public static void save(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Path path = server.getRunDirectory().resolve(SESSION_PATH);
        Properties props = new Properties();
        List<Session> sessions = new ArrayList<>(SESSIONS.values());
        sessions.sort(Comparator.comparing(s -> s.playerId.toString()));
        props.setProperty("count", Integer.toString(sessions.size()));
        for (int i = 0; i < sessions.size(); i++) {
            Session s = sessions.get(i);
            String prefix = "session." + i + ".";
            props.setProperty(prefix + "player", s.playerId.toString());
            props.setProperty(prefix + "world", s.worldId);
            props.setProperty(prefix + "from", encodePos(s.from));
            props.setProperty(prefix + "to", encodePos(s.to));
            props.setProperty(prefix + "preset", s.preset);
            props.setProperty(prefix + "order", String.join(",", s.districtOrder));
            props.setProperty(prefix + "next", Integer.toString(s.nextDistrictIndex));
            props.setProperty(prefix + "roadsIncluded", Boolean.toString(s.roadsIncluded));
            props.setProperty(prefix + "stage", s.stage.name());
            props.setProperty(prefix + "activeDistrict", s.activeDistrict == null ? "" : s.activeDistrict);
            props.setProperty(prefix + "awaitingTag", s.awaitingTag == null ? "" : s.awaitingTag);
            props.setProperty(prefix + "lastSummary", s.lastSummary == null ? "" : s.lastSummary);
            props.setProperty(prefix + "queuedDistricts", Integer.toString(s.queuedDistricts));
            props.setProperty(prefix + "builtDistricts", Integer.toString(s.builtDistricts));
            props.setProperty(prefix + "skippedDistricts", Integer.toString(s.skippedDistricts));
            props.setProperty(prefix + "failedDistricts", Integer.toString(s.failedDistricts));
            props.setProperty(prefix + "updatedAt", Long.toString(s.updatedAtEpochMs));
        }
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Bladelow city autoplay sessions");
            }
            dirty = false;
            lastSaveError = "";
        } catch (IOException ex) {
            lastSaveError = ex.getMessage();
        }
    }

    private static void queueNextDistrict(
        MinecraftServer server,
        ServerPlayerEntity player,
        ServerWorld world,
        Session session
    ) {
        while (session.nextDistrictIndex < session.districtOrder.size()) {
            String district = session.districtOrder.get(session.nextDistrictIndex);
            BlueprintLibrary.BuildPlan plan = BlueprintLibrary.resolveTownFill(
                world,
                session.playerId,
                session.from,
                session.to,
                district,
                true,
                !session.roadsIncluded
            );

            if (!plan.ok()) {
                String msg = plan.message() == null ? "" : plan.message();
                if (isSkippablePlanMiss(msg, district)) {
                    session.skippedDistricts++;
                    session.nextDistrictIndex++;
                    session.touch("skip " + district + " (" + msg + ")");
                    player.sendMessage(blueText("[Bladelow] city autoplay skip " + district + ": " + msg), false);
                    continue;
                }
                session.failedDistricts++;
                session.stage = Stage.PAUSED;
                session.touch("planner error " + district + ": " + msg);
                player.sendMessage(blueText("[Bladelow] city autoplay paused (" + district + "): " + msg), false);
                return;
            }
            if (plan.targets().isEmpty()) {
                session.skippedDistricts++;
                session.nextDistrictIndex++;
                session.touch("skip " + district + " (0 targets)");
                continue;
            }

            MaterialResolver.Resolution resolution = MaterialResolver.resolve(player, plan.blockStates());
            if (!resolution.summary().isBlank()) {
                player.sendMessage(blueText("[Bladelow] " + resolution.summary()), false);
            }
            List<BlockState> states = resolution.blockStates();
            List<BlockPos> targets = plan.targets();
            TerrainPrep prep = buildTerrainPrep(world, states, targets);

            session.activeDistrict = district;
            session.pendingStates = states;
            session.pendingTargets = targets;
            session.queuedDistricts++;
            session.touch("queued " + district + " targets=" + targets.size());

            if (!prep.targets().isEmpty()) {
                String prepTag = "autocity:prep:" + district + ":" + session.nextDistrictIndex;
                if (!queuePlacementJob(server, world.getRegistryKey(), session.playerId, prep.states(), prep.targets(), prepTag)) {
                    session.failedDistricts++;
                    session.stage = Stage.PAUSED;
                    session.touch("failed terrain prep queue");
                    player.sendMessage(blueText("[Bladelow] city autoplay paused: failed to queue terrain prep"), false);
                    return;
                }
                session.awaitingTag = prepTag;
                session.stage = Stage.PREP_RUNNING;
                player.sendMessage(blueText(
                    "[Bladelow] city autoplay terrain prep " + district
                        + " clears=" + prep.clears()
                        + " supports=" + prep.supports()
                ), false);
                return;
            }

            if (!queueDistrictBuild(server, player, world, session)) {
                session.failedDistricts++;
                session.stage = Stage.PAUSED;
                session.touch("failed build queue");
                player.sendMessage(blueText("[Bladelow] city autoplay paused: failed to queue district build"), false);
            }
            return;
        }
    }

    private static boolean queueDistrictBuild(
        MinecraftServer server,
        ServerPlayerEntity player,
        ServerWorld world,
        Session session
    ) {
        List<BlockState> states = session.pendingStates;
        List<BlockPos> targets = session.pendingTargets;
        if (states == null || targets == null || states.isEmpty() || targets.isEmpty() || states.size() != targets.size()) {
            BlueprintLibrary.BuildPlan fallback = BlueprintLibrary.resolveTownFill(
                world,
                session.playerId,
                session.from,
                session.to,
                session.activeDistrict,
                false,
                false
            );
            if (!fallback.ok() || fallback.targets().isEmpty()) {
                return false;
            }
            states = fallback.blockStates();
            targets = fallback.targets();
        }

        String tag = "autocity:district:" + session.activeDistrict + ":" + session.nextDistrictIndex;
        if (!queuePlacementJob(server, world.getRegistryKey(), session.playerId, states, targets, tag)) {
            return false;
        }
        session.awaitingTag = tag;
        session.stage = Stage.BUILD_RUNNING;
        session.touch("district build queued");
        player.sendMessage(blueText(
            "[Bladelow] city autoplay building " + session.activeDistrict
                + " targets=" + targets.size()
                + " (" + (session.nextDistrictIndex + 1) + "/" + session.districtOrder.size() + ")"
        ), false);
        return true;
    }

    private static boolean queuePlacementJob(
        MinecraftServer server,
        RegistryKey<World> worldKey,
        UUID playerId,
        List<BlockState> states,
        List<BlockPos> targets,
        String tag
    ) {
        if (server == null || worldKey == null || playerId == null || states == null || targets == null
            || states.size() != targets.size() || states.isEmpty()) {
            return false;
        }
        PlacementJob job = new PlacementJob(
            playerId,
            worldKey,
            states,
            targets,
            tag,
            autoplayRuntimeSnapshot()
        );
        PlacementJobRunner.queueOrPreview(server, job);
        return true;
    }

    private static BuildRuntimeSettings.Snapshot autoplayRuntimeSnapshot() {
        BuildRuntimeSettings.Snapshot base = BuildRuntimeSettings.snapshot();
        return new BuildRuntimeSettings.Snapshot(
            base.smartMoveEnabled(),
            base.reachDistance(),
            base.moveMode(),
            false, // never require strict-air in city autoplay
            false, // never require manual preview in city autoplay
            base.targetSchedulerEnabled(),
            Math.max(8, base.schedulerLookahead()),
            true,
            Math.max(2, base.maxTargetDeferrals()),
            true,
            base.pathTraceEnabled(),
            base.pathTraceParticles()
        );
    }

    private static TerrainPrep buildTerrainPrep(
        ServerWorld world,
        List<BlockState> states,
        List<BlockPos> targets
    ) {
        if (world == null || states == null || targets == null || states.size() != targets.size() || states.isEmpty()) {
            return TerrainPrep.empty();
        }
        int minY = Integer.MAX_VALUE;
        for (BlockPos pos : targets) {
            minY = Math.min(minY, pos.getY());
        }
        if (minY == Integer.MAX_VALUE) {
            return TerrainPrep.empty();
        }

        LinkedHashMap<Long, BlockState> prepMap = new LinkedHashMap<>();
        int clears = 0;
        int supports = 0;

        for (int i = 0; i < targets.size(); i++) {
            BlockPos target = targets.get(i);
            BlockState desired = states.get(i);
            BlockState existing = world.getBlockState(target);

            if (shouldClear(existing, desired)) {
                long key = target.asLong();
                if (!prepMap.containsKey(key)) {
                    prepMap.put(key, Blocks.AIR.getDefaultState());
                    clears++;
                }
            }

            if (target.getY() == minY && desired != null && desired.getBlock() != Blocks.AIR) {
                BlockPos below = target.down();
                BlockState belowState = world.getBlockState(below);
                if (needsSupport(belowState)) {
                    long supportKey = below.asLong();
                    if (!prepMap.containsKey(supportKey)) {
                        prepMap.put(supportKey, supportStateFor(desired));
                        supports++;
                    }
                }
            }
        }

        if (prepMap.isEmpty()) {
            return TerrainPrep.empty();
        }
        List<BlockState> prepStates = new ArrayList<>(prepMap.size());
        List<BlockPos> prepTargets = new ArrayList<>(prepMap.size());
        for (Map.Entry<Long, BlockState> entry : prepMap.entrySet()) {
            prepTargets.add(BlockPos.fromLong(entry.getKey()));
            prepStates.add(entry.getValue());
        }
        return new TerrainPrep(List.copyOf(prepStates), List.copyOf(prepTargets), clears, supports);
    }

    private static boolean shouldClear(BlockState existing, BlockState desired) {
        if (existing == null || existing.isAir()) {
            return false;
        }
        if (desired != null && existing.equals(desired)) {
            return false;
        }
        if (existing.isReplaceable()) {
            return true;
        }
        if (!existing.getFluidState().isEmpty()) {
            return true;
        }
        Identifier id = net.minecraft.registry.Registries.BLOCK.getId(existing.getBlock());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("leaves")
            || path.contains("vine")
            || path.contains("grass")
            || path.contains("flower")
            || path.contains("mushroom")
            || path.contains("fern")
            || path.contains("roots")
            || path.contains("bush")
            || path.contains("sapling");
    }

    private static boolean needsSupport(BlockState belowState) {
        if (belowState == null) {
            return true;
        }
        if (belowState.isAir()) {
            return true;
        }
        if (belowState.isReplaceable()) {
            return true;
        }
        return !belowState.getFluidState().isEmpty();
    }

    private static BlockState supportStateFor(BlockState desired) {
        if (desired == null || desired.getBlock() == Blocks.AIR) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }
        String path = net.minecraft.registry.Registries.BLOCK.getId(desired.getBlock()).getPath();
        if (path.contains("slab") || path.contains("stairs") || path.contains("wall") || path.contains("fence")) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }
        return desired;
    }

    private static boolean isSkippablePlanMiss(String msg, String district) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.startsWith("no " + district + " zones in selected area")
            || lower.startsWith("no clear lots fit town blueprints in selected area");
    }

    private static List<String> scheduleDistrictOrder(List<TownZoneStore.Zone> zones, BlockPos from, BlockPos to) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());
        LinkedHashMap<String, Integer> areaByType = new LinkedHashMap<>();
        for (String district : BASE_DISTRICT_ORDER) {
            areaByType.put(district, 0);
        }
        for (TownZoneStore.Zone zone : zones) {
            if (zone == null) {
                continue;
            }
            int ix0 = Math.max(minX, zone.minX());
            int ix1 = Math.min(maxX, zone.maxX());
            int iz0 = Math.max(minZ, zone.minZ());
            int iz1 = Math.min(maxZ, zone.maxZ());
            if (ix0 > ix1 || iz0 > iz1) {
                continue;
            }
            int area = (ix1 - ix0 + 1) * (iz1 - iz0 + 1);
            areaByType.merge(zone.type(), area, Integer::sum);
        }

        LinkedHashSet<String> dedup = new LinkedHashSet<>(BASE_DISTRICT_ORDER);
        List<String> order = new ArrayList<>(dedup);
        order.sort((a, b) -> {
            int byArea = Integer.compare(areaByType.getOrDefault(b, 0), areaByType.getOrDefault(a, 0));
            if (byArea != 0) {
                return byArea;
            }
            return Integer.compare(BASE_DISTRICT_ORDER.indexOf(a), BASE_DISTRICT_ORDER.indexOf(b));
        });
        return List.copyOf(order);
    }

    private static String encodePos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos parsePos(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        Integer x = tryParseInt(parts[0]);
        Integer y = tryParseInt(parts[1]);
        Integer z = tryParseInt(parts[2]);
        if (x == null || y == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static int parseInt(String raw, int fallback) {
        Integer parsed = tryParseInt(raw);
        return parsed == null ? fallback : parsed;
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Integer tryParseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Stage parseStage(String raw) {
        if (raw == null || raw.isBlank()) {
            return Stage.IDLE;
        }
        try {
            return Stage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Stage.IDLE;
        }
    }

    private static List<String> decodeOrder(String raw) {
        if (raw == null || raw.isBlank()) {
            return BASE_DISTRICT_ORDER;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String id = TownDistrictType.normalize(token);
            if (!id.isBlank()) {
                out.add(id);
            }
        }
        for (String base : BASE_DISTRICT_ORDER) {
            out.add(base);
        }
        return List.copyOf(out);
    }

    private static String formatAgeMillis(long millis) {
        long total = Math.max(0L, millis / 1000L);
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) {
            return h + "h" + m + "m" + s + "s";
        }
        if (m > 0) {
            return m + "m" + s + "s";
        }
        return s + "s";
    }

    private static Text blueText(String msg) {
        return Text.literal("[Bladelow] " + msg).formatted(Formatting.AQUA);
    }

    private record TerrainPrep(List<BlockState> states, List<BlockPos> targets, int clears, int supports) {
        static TerrainPrep empty() {
            return new TerrainPrep(List.of(), List.of(), 0, 0);
        }
    }
}
