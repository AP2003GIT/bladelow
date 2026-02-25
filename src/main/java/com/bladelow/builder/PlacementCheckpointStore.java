package com.bladelow.builder;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class PlacementCheckpointStore {
    private static final int CHECKPOINT_VERSION = 1;
    private static final Path CHECKPOINT_PATH = Path.of("config", "bladelow", "jobs-checkpoint.properties");

    private PlacementCheckpointStore() {
    }

    public static void save(MinecraftServer server, List<PlacementJob> activeJobs, List<PlacementJob> pendingJobs) {
        Properties props = new Properties();
        props.setProperty("version", Integer.toString(CHECKPOINT_VERSION));

        int idx = 0;
        for (PlacementJob job : activeJobs) {
            idx = writeJob(props, idx, "active", job);
        }
        for (PlacementJob job : pendingJobs) {
            idx = writeJob(props, idx, "pending", job);
        }
        props.setProperty("job.count", Integer.toString(idx));

        Path file = server.getRunDirectory().resolve(CHECKPOINT_PATH);
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }

        try {
            Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "Bladelow active/pending build checkpoints");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Ignore checkpoint write failures.
        }
    }

    public static LoadResult load(MinecraftServer server) {
        Path file = server.getRunDirectory().resolve(CHECKPOINT_PATH);
        if (!Files.exists(file)) {
            return new LoadResult(List.of(), List.of(), 0);
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ex) {
            return new LoadResult(List.of(), List.of(), 0);
        }

        int count = parseInt(props.getProperty("job.count"), 0);
        List<PlacementJob> active = new ArrayList<>();
        List<PlacementJob> pending = new ArrayList<>();
        int dropped = 0;

        for (int i = 0; i < count; i++) {
            String prefix = "job." + i + ".";
            String state = props.getProperty(prefix + "state", "pending");
            PlacementJob job = readJob(props, prefix);
            if (job == null) {
                dropped++;
                continue;
            }
            if ("active".equalsIgnoreCase(state)) {
                active.add(job);
            } else {
                pending.add(job);
            }
        }
        return new LoadResult(active, pending, dropped);
    }

    private static int writeJob(Properties props, int index, String state, PlacementJob job) {
        PlacementJob.JobSnapshot snapshot = job.snapshot();
        if (snapshot == null) {
            return index;
        }

        String prefix = "job." + index + ".";
        props.setProperty(prefix + "state", state);
        props.setProperty(prefix + "player", snapshot.playerId().toString());
        props.setProperty(prefix + "world", snapshot.worldId());
        props.setProperty(prefix + "tag", snapshot.tag());
        props.setProperty(prefix + "cursor", Integer.toString(snapshot.cursor()));
        props.setProperty(prefix + "placed", Integer.toString(snapshot.placed()));
        props.setProperty(prefix + "skipped", Integer.toString(snapshot.skipped()));
        props.setProperty(prefix + "failed", Integer.toString(snapshot.failed()));
        props.setProperty(prefix + "moved", Integer.toString(snapshot.moved()));
        props.setProperty(prefix + "deferred", Integer.toString(snapshot.deferred()));
        props.setProperty(prefix + "reprioritized", Integer.toString(snapshot.reprioritized()));
        props.setProperty(prefix + "alreadyPlaced", Integer.toString(snapshot.alreadyPlaced()));
        props.setProperty(prefix + "blocked", Integer.toString(snapshot.blocked()));
        props.setProperty(prefix + "protectedBlocked", Integer.toString(snapshot.protectedBlocked()));
        props.setProperty(prefix + "noReach", Integer.toString(snapshot.noReach()));
        props.setProperty(prefix + "mlRejected", Integer.toString(snapshot.mlRejected()));
        props.setProperty(prefix + "stuckEvents", Integer.toString(snapshot.stuckEvents()));
        props.setProperty(prefix + "pathReplans", Integer.toString(snapshot.pathReplans()));
        props.setProperty(prefix + "backtracks", Integer.toString(snapshot.backtracks()));
        props.setProperty(prefix + "blacklistHits", Integer.toString(snapshot.blacklistHits()));
        props.setProperty(prefix + "totalScore", Double.toString(snapshot.totalScore()));
        props.setProperty(prefix + "ticks", Integer.toString(snapshot.ticks()));
        props.setProperty(prefix + "lastEvent", snapshot.lastEvent() == null ? "" : snapshot.lastEvent());
        props.setProperty(prefix + "node", snapshot.node() == null ? PlacementJob.TaskNode.MOVE.name() : snapshot.node().name());
        props.setProperty(prefix + "recoverReason", snapshot.recoverReason() == null ? PlacementJob.RecoverReason.NONE.name() : snapshot.recoverReason().name());
        props.setProperty(prefix + "recoverDetail", snapshot.recoverDetail() == null ? "" : snapshot.recoverDetail());

        BuildRuntimeSettings.Snapshot rt = snapshot.runtimeSettings();
        props.setProperty(prefix + "rt.smartMove", Boolean.toString(rt.smartMoveEnabled()));
        props.setProperty(prefix + "rt.reach", Double.toString(rt.reachDistance()));
        props.setProperty(prefix + "rt.mode", rt.moveMode().name());
        props.setProperty(prefix + "rt.strictAir", Boolean.toString(rt.strictAirOnly()));
        props.setProperty(prefix + "rt.preview", Boolean.toString(rt.previewBeforeBuild()));
        props.setProperty(prefix + "rt.scheduler", Boolean.toString(rt.targetSchedulerEnabled()));
        props.setProperty(prefix + "rt.lookahead", Integer.toString(rt.schedulerLookahead()));
        props.setProperty(prefix + "rt.defer", Boolean.toString(rt.deferUnreachableTargets()));
        props.setProperty(prefix + "rt.maxDefers", Integer.toString(rt.maxTargetDeferrals()));
        props.setProperty(prefix + "rt.autoResume", Boolean.toString(rt.autoResumeEnabled()));
        props.setProperty(prefix + "rt.trace", Boolean.toString(rt.pathTraceEnabled()));
        props.setProperty(prefix + "rt.traceParticles", Boolean.toString(rt.pathTraceParticles()));

        List<PlacementJob.EntrySnapshot> entries = snapshot.entries();
        props.setProperty(prefix + "entries.count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            PlacementJob.EntrySnapshot entry = entries.get(i);
            String entryPrefix = prefix + "entry." + i + ".";
            props.setProperty(entryPrefix + "block", entry.blockId());
            props.setProperty(entryPrefix + "x", Integer.toString(entry.x()));
            props.setProperty(entryPrefix + "y", Integer.toString(entry.y()));
            props.setProperty(entryPrefix + "z", Integer.toString(entry.z()));
            props.setProperty(entryPrefix + "attempts", Integer.toString(entry.attempts()));
            props.setProperty(entryPrefix + "deferrals", Integer.toString(entry.deferrals()));
        }

        return index + 1;
    }

    private static PlacementJob readJob(Properties props, String prefix) {
        UUID playerId;
        try {
            playerId = UUID.fromString(props.getProperty(prefix + "player", ""));
        } catch (IllegalArgumentException ex) {
            return null;
        }

        String world = props.getProperty(prefix + "world", "").trim();
        String tag = props.getProperty(prefix + "tag", "restored").trim();
        if (world.isBlank()) {
            return null;
        }

        BuildRuntimeSettings.MoveMode moveMode;
        try {
            moveMode = BuildRuntimeSettings.MoveMode.valueOf(props.getProperty(prefix + "rt.mode", BuildRuntimeSettings.MoveMode.WALK.name()));
        } catch (IllegalArgumentException ex) {
            moveMode = BuildRuntimeSettings.MoveMode.WALK;
        }

        BuildRuntimeSettings.Snapshot runtimeSettings = new BuildRuntimeSettings.Snapshot(
            parseBoolean(props.getProperty(prefix + "rt.smartMove"), true),
            parseDouble(props.getProperty(prefix + "rt.reach"), 4.5),
            moveMode,
            parseBoolean(props.getProperty(prefix + "rt.strictAir"), false),
            parseBoolean(props.getProperty(prefix + "rt.preview"), false),
            parseBoolean(props.getProperty(prefix + "rt.scheduler"), true),
            parseInt(props.getProperty(prefix + "rt.lookahead"), 14),
            parseBoolean(props.getProperty(prefix + "rt.defer"), true),
            parseInt(props.getProperty(prefix + "rt.maxDefers"), 2),
            parseBoolean(props.getProperty(prefix + "rt.autoResume"), true),
            parseBoolean(props.getProperty(prefix + "rt.trace"), false),
            parseBoolean(props.getProperty(prefix + "rt.traceParticles"), false)
        );

        int entryCount = parseInt(props.getProperty(prefix + "entries.count"), 0);
        if (entryCount <= 0) {
            return null;
        }

        List<PlacementJob.EntrySnapshot> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            String entryPrefix = prefix + "entry." + i + ".";
            String blockId = props.getProperty(entryPrefix + "block", "").trim();
            if (blockId.isBlank()) {
                return null;
            }
            int x = parseInt(props.getProperty(entryPrefix + "x"), 0);
            int y = parseInt(props.getProperty(entryPrefix + "y"), 0);
            int z = parseInt(props.getProperty(entryPrefix + "z"), 0);
            int attempts = parseInt(props.getProperty(entryPrefix + "attempts"), 0);
            int deferrals = parseInt(props.getProperty(entryPrefix + "deferrals"), 0);
            entries.add(new PlacementJob.EntrySnapshot(blockId, x, y, z, attempts, deferrals));
        }

        PlacementJob.JobSnapshot snapshot = new PlacementJob.JobSnapshot(
            playerId,
            world,
            tag.isBlank() ? "restored" : tag,
            runtimeSettings,
            parseInt(props.getProperty(prefix + "cursor"), 0),
            parseInt(props.getProperty(prefix + "placed"), 0),
            parseInt(props.getProperty(prefix + "skipped"), 0),
            parseInt(props.getProperty(prefix + "failed"), 0),
            parseInt(props.getProperty(prefix + "moved"), 0),
            parseInt(props.getProperty(prefix + "deferred"), 0),
            parseInt(props.getProperty(prefix + "reprioritized"), 0),
            parseInt(props.getProperty(prefix + "alreadyPlaced"), 0),
            parseInt(props.getProperty(prefix + "blocked"), 0),
            parseInt(props.getProperty(prefix + "protectedBlocked"), 0),
            parseInt(props.getProperty(prefix + "noReach"), 0),
            parseInt(props.getProperty(prefix + "mlRejected"), 0),
            parseInt(props.getProperty(prefix + "stuckEvents"), 0),
            parseInt(props.getProperty(prefix + "pathReplans"), 0),
            parseInt(props.getProperty(prefix + "backtracks"), 0),
            parseInt(props.getProperty(prefix + "blacklistHits"), 0),
            parseDouble(props.getProperty(prefix + "totalScore"), 0.0),
            parseInt(props.getProperty(prefix + "ticks"), 0),
            props.getProperty(prefix + "lastEvent", "restored"),
            parseTaskNode(props.getProperty(prefix + "node")),
            parseRecoverReason(props.getProperty(prefix + "recoverReason")),
            props.getProperty(prefix + "recoverDetail", ""),
            entries
        );
        return PlacementJob.fromSnapshot(snapshot);
    }

    private static PlacementJob.TaskNode parseTaskNode(String value) {
        if (value == null) {
            return PlacementJob.TaskNode.MOVE;
        }
        try {
            return PlacementJob.TaskNode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PlacementJob.TaskNode.MOVE;
        }
    }

    private static PlacementJob.RecoverReason parseRecoverReason(String value) {
        if (value == null) {
            return PlacementJob.RecoverReason.NONE;
        }
        try {
            return PlacementJob.RecoverReason.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PlacementJob.RecoverReason.NONE;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return fallback;
    }

    public record LoadResult(List<PlacementJob> active, List<PlacementJob> pending, int dropped) {
        public int restoredCount() {
            return active.size() + pending.size();
        }
    }
}
