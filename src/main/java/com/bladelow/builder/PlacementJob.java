package com.bladelow.builder;

import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PlacementJob {
    private final UUID playerId;
    private final RegistryKey<World> worldKey;
    private final List<Entry> entries;
    private final String tag;
    private final BuildRuntimeSettings.Snapshot runtimeSettings;

    private int cursor;
    private int placed;
    private int skipped;
    private int failed;
    private int moved;
    private int deferred;
    private int reprioritized;
    private int alreadyPlaced;
    private int blocked;
    private int protectedBlocked;
    private int noReach;
    private int mlRejected;
    private int stuckEvents;
    private int pathReplans;
    private int backtracks;
    private int blacklistHits;
    private double totalScore;
    private int ticks;
    private String lastEvent = "queued";
    private TaskNode node = TaskNode.MOVE;
    private RecoverReason recoverReason = RecoverReason.NONE;
    private String recoverDetail = "";

    public PlacementJob(
        UUID playerId,
        RegistryKey<World> worldKey,
        List<Block> blocks,
        List<BlockPos> targets,
        String tag,
        BuildRuntimeSettings.Snapshot runtimeSettings
    ) {
        if (blocks.size() != targets.size()) {
            throw new IllegalArgumentException("blocks and targets size mismatch");
        }
        this.playerId = playerId;
        this.worldKey = worldKey;
        this.entries = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            this.entries.add(new Entry(blocks.get(i), targets.get(i)));
        }
        this.tag = tag;
        this.runtimeSettings = runtimeSettings;
    }

    public UUID playerId() {
        return playerId;
    }

    public RegistryKey<World> worldKey() {
        return worldKey;
    }

    public String tag() {
        return tag;
    }

    public BuildRuntimeSettings.Snapshot runtimeSettings() {
        return runtimeSettings;
    }

    public int totalTargets() {
        return entries.size();
    }

    public int cursor() {
        return cursor;
    }

    public int placedCount() {
        return placed;
    }

    public int skippedCount() {
        return skipped;
    }

    public int failedCount() {
        return failed;
    }

    public int movedCount() {
        return moved;
    }

    public int deferredCount() {
        return deferred;
    }

    public int reprioritizedCount() {
        return reprioritized;
    }

    public int alreadyPlacedCount() {
        return alreadyPlaced;
    }

    public int blockedCount() {
        return blocked;
    }

    public int protectedBlockedCount() {
        return protectedBlocked;
    }

    public int noReachCount() {
        return noReach;
    }

    public int mlRejectedCount() {
        return mlRejected;
    }

    public int stuckEventsCount() {
        return stuckEvents;
    }

    public int pathReplansCount() {
        return pathReplans;
    }

    public int backtracksCount() {
        return backtracks;
    }

    public int blacklistHitsCount() {
        return blacklistHits;
    }

    public double averageScore() {
        return totalScore / Math.max(1, totalTargets());
    }

    public double noReachPercent() {
        return (noReach * 100.0) / Math.max(1, totalTargets());
    }

    public String lastEvent() {
        return lastEvent;
    }

    public BlockPos currentTarget() {
        return entries.get(cursor).target;
    }

    public Block currentBlock() {
        return entries.get(cursor).block;
    }

    public BlockPos targetAt(int idx) {
        return entries.get(idx).target;
    }

    public void advance() {
        cursor++;
        resetTaskNode();
    }

    public int currentAttempts() {
        return entries.get(cursor).attempts;
    }

    public int incrementCurrentAttempts() {
        Entry entry = entries.get(cursor);
        entry.attempts++;
        return entry.attempts;
    }

    public int currentDeferrals() {
        return entries.get(cursor).deferrals;
    }

    public boolean selectBestTargetNear(BlockPos from, int lookahead) {
        if (isComplete() || lookahead <= 1) {
            return false;
        }

        int end = Math.min(entries.size() - 1, cursor + lookahead - 1);
        int bestIndex = cursor;
        double bestScore = score(entries.get(cursor), from) + 0.001;

        for (int i = cursor + 1; i <= end; i++) {
            Entry candidate = entries.get(i);
            double score = score(candidate, from) + (i - cursor) * 0.12;
            if (score < bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        if (bestIndex == cursor) {
            return false;
        }

        Entry entry = entries.remove(bestIndex);
        entries.add(cursor, entry);
        reprioritized++;
        return true;
    }

    public boolean deferCurrentToTail() {
        if (isComplete() || cursor >= entries.size() - 1) {
            return false;
        }

        Entry current = entries.remove(cursor);
        current.deferrals++;
        current.attempts = 0;
        entries.add(current);
        deferred++;
        resetTaskNode();
        return true;
    }

    private static double score(Entry entry, BlockPos from) {
        double ox = from.getX() + 0.5;
        double oy = from.getY() + 0.5;
        double oz = from.getZ() + 0.5;
        double distance = entry.target.getSquaredDistance(ox, oy, oz);
        double attemptPenalty = entry.attempts * 24.0;
        double deferPenalty = entry.deferrals * 96.0;
        return distance + attemptPenalty + deferPenalty;
    }

    public void recordPlaced() {
        placed++;
        noteEvent("placed");
    }

    public void recordSkipped() {
        skipped++;
        noteEvent("skipped");
    }

    public void recordFailed() {
        failed++;
        noteEvent("failed");
    }

    public void recordMoved() {
        moved++;
        noteEvent("moved");
    }

    public void recordAlreadyPlaced() {
        alreadyPlaced++;
        noteEvent("already");
    }

    public void recordBlocked() {
        blocked++;
        noteEvent("blocked");
    }

    public void recordProtectedBlocked() {
        protectedBlocked++;
        noteEvent("protected");
    }

    public void recordNoReach() {
        noReach++;
        noteEvent("out_of_reach");
    }

    public void recordMlRejected() {
        mlRejected++;
        noteEvent("ml_skip");
    }

    public void recordStuckEvent() {
        stuckEvents++;
        noteEvent("stuck");
    }

    public void recordPathReplan() {
        pathReplans++;
        noteEvent("replan");
    }

    public void recordBacktrack() {
        backtracks++;
        noteEvent("backtrack");
    }

    public void recordBlacklistHits(int hits) {
        if (hits <= 0) {
            return;
        }
        blacklistHits += hits;
    }

    public void noteEvent(String event) {
        if (event == null) {
            return;
        }
        String trimmed = event.trim();
        if (trimmed.isBlank()) {
            return;
        }
        if (trimmed.length() > 96) {
            trimmed = trimmed.substring(0, 96);
        }
        lastEvent = trimmed;
    }

    public TaskNode currentNode() {
        return node;
    }

    public void setNode(TaskNode node) {
        this.node = node == null ? TaskNode.MOVE : node;
    }

    public RecoverReason recoverReason() {
        return recoverReason;
    }

    public String recoverDetail() {
        return recoverDetail;
    }

    public void startRecover(RecoverReason reason, String detail) {
        this.node = TaskNode.RECOVER;
        this.recoverReason = reason == null ? RecoverReason.UNKNOWN : reason;
        if (detail == null) {
            this.recoverDetail = "";
        } else {
            String trimmed = detail.trim();
            this.recoverDetail = trimmed.length() > 96 ? trimmed.substring(0, 96) : trimmed;
        }
    }

    public void resetTaskNode() {
        this.node = TaskNode.MOVE;
        this.recoverReason = RecoverReason.NONE;
        this.recoverDetail = "";
    }

    public void addScore(double score) {
        totalScore += score;
    }

    public void tick() {
        ticks++;
    }

    public boolean shouldReportProgress() {
        return ticks % 40 == 0;
    }

    public boolean isComplete() {
        return cursor >= entries.size();
    }

    public String progressSummary() {
        String current = "";
        if (!isComplete()) {
            BlockPos t = currentTarget();
            current = " target=(" + t.getX() + "," + t.getY() + "," + t.getZ() + ")"
                + " attempts=" + currentAttempts()
                + " defers=" + currentDeferrals();
        }
        return "[Bladelow] " + tag + " progress " + cursor + "/" + totalTargets()
            + " node=" + node.name().toLowerCase(Locale.ROOT)
            + " placed=" + placed
            + " skipped=" + skipped
            + " failed=" + failed
            + " moved=" + moved
            + " deferred=" + deferred
            + " replan=" + reprioritized
            + " last=" + lastEvent
            + current;
    }

    public String completionSummary() {
        double avgScore = totalScore / Math.max(1, totalTargets());
        double noReachPct = (noReach * 100.0) / Math.max(1, totalTargets());
        return "[Bladelow] " + tag
            + " targets=" + totalTargets()
            + " node=" + node.name().toLowerCase(Locale.ROOT)
            + " placed=" + placed
            + " skipped=" + skipped
            + " failed=" + failed
            + " moved=" + moved
            + " deferred=" + deferred
            + " replan=" + reprioritized
            + " already=" + alreadyPlaced
            + " blocked=" + blocked
            + " protected=" + protectedBlocked
            + " noReach=" + noReach
            + " noReachPct=" + String.format(Locale.ROOT, "%.1f", noReachPct)
            + " mlSkip=" + mlRejected
            + " stuck=" + stuckEvents
            + " replans=" + pathReplans
            + " backtracks=" + backtracks
            + " blHits=" + blacklistHits
            + " avgScore=" + String.format(Locale.ROOT, "%.3f", avgScore)
            + " last=" + lastEvent
            + " " + runtimeSettings.summary();
    }

    public JobSnapshot snapshot() {
        List<EntrySnapshot> entrySnapshots = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Identifier blockId = Registries.BLOCK.getId(entry.block);
            if (blockId == null) {
                return null;
            }
            entrySnapshots.add(new EntrySnapshot(
                blockId.toString(),
                entry.target.getX(),
                entry.target.getY(),
                entry.target.getZ(),
                entry.attempts,
                entry.deferrals
            ));
        }
        return new JobSnapshot(
            playerId,
            worldKey.getValue().toString(),
            tag,
            runtimeSettings,
            cursor,
            placed,
            skipped,
            failed,
            moved,
            deferred,
            reprioritized,
            alreadyPlaced,
            blocked,
            protectedBlocked,
            noReach,
            mlRejected,
            stuckEvents,
            pathReplans,
            backtracks,
            blacklistHits,
            totalScore,
            ticks,
            lastEvent,
            node,
            recoverReason,
            recoverDetail,
            entrySnapshots
        );
    }

    public static PlacementJob fromSnapshot(JobSnapshot snapshot) {
        if (snapshot == null || snapshot.entries() == null || snapshot.entries().isEmpty()) {
            return null;
        }
        Identifier worldId = Identifier.tryParse(snapshot.worldId());
        if (worldId == null) {
            return null;
        }

        List<Block> blocks = new ArrayList<>(snapshot.entries().size());
        List<BlockPos> targets = new ArrayList<>(snapshot.entries().size());
        for (EntrySnapshot entry : snapshot.entries()) {
            if (entry == null) {
                return null;
            }
            Identifier blockId = Identifier.tryParse(entry.blockId());
            if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
                return null;
            }
            Block block = Registries.BLOCK.get(blockId);
            if (block == null) {
                return null;
            }
            blocks.add(block);
            targets.add(new BlockPos(entry.x(), entry.y(), entry.z()));
        }

        PlacementJob job = new PlacementJob(
            snapshot.playerId(),
            RegistryKey.of(RegistryKeys.WORLD, worldId),
            blocks,
            targets,
            snapshot.tag(),
            snapshot.runtimeSettings()
        );

        for (int i = 0; i < snapshot.entries().size(); i++) {
            EntrySnapshot entrySnapshot = snapshot.entries().get(i);
            Entry entry = job.entries.get(i);
            entry.attempts = Math.max(0, entrySnapshot.attempts());
            entry.deferrals = Math.max(0, entrySnapshot.deferrals());
        }

        job.cursor = Math.max(0, Math.min(snapshot.cursor(), job.entries.size()));
        job.placed = Math.max(0, snapshot.placed());
        job.skipped = Math.max(0, snapshot.skipped());
        job.failed = Math.max(0, snapshot.failed());
        job.moved = Math.max(0, snapshot.moved());
        job.deferred = Math.max(0, snapshot.deferred());
        job.reprioritized = Math.max(0, snapshot.reprioritized());
        job.alreadyPlaced = Math.max(0, snapshot.alreadyPlaced());
        job.blocked = Math.max(0, snapshot.blocked());
        job.protectedBlocked = Math.max(0, snapshot.protectedBlocked());
        job.noReach = Math.max(0, snapshot.noReach());
        job.mlRejected = Math.max(0, snapshot.mlRejected());
        job.stuckEvents = Math.max(0, snapshot.stuckEvents());
        job.pathReplans = Math.max(0, snapshot.pathReplans());
        job.backtracks = Math.max(0, snapshot.backtracks());
        job.blacklistHits = Math.max(0, snapshot.blacklistHits());
        job.totalScore = Math.max(0.0, snapshot.totalScore());
        job.ticks = Math.max(0, snapshot.ticks());
        job.noteEvent(snapshot.lastEvent());
        job.node = snapshot.node() == null ? TaskNode.MOVE : snapshot.node();
        job.recoverReason = snapshot.recoverReason() == null ? RecoverReason.NONE : snapshot.recoverReason();
        job.recoverDetail = snapshot.recoverDetail() == null ? "" : snapshot.recoverDetail();
        return job;
    }

    public record EntrySnapshot(
        String blockId,
        int x,
        int y,
        int z,
        int attempts,
        int deferrals
    ) {
    }

    public record JobSnapshot(
        UUID playerId,
        String worldId,
        String tag,
        BuildRuntimeSettings.Snapshot runtimeSettings,
        int cursor,
        int placed,
        int skipped,
        int failed,
        int moved,
        int deferred,
        int reprioritized,
        int alreadyPlaced,
        int blocked,
        int protectedBlocked,
        int noReach,
        int mlRejected,
        int stuckEvents,
        int pathReplans,
        int backtracks,
        int blacklistHits,
        double totalScore,
        int ticks,
        String lastEvent,
        TaskNode node,
        RecoverReason recoverReason,
        String recoverDetail,
        List<EntrySnapshot> entries
    ) {
    }

    public enum TaskNode {
        MOVE,
        ALIGN,
        PLACE,
        RECOVER
    }

    public enum RecoverReason {
        NONE,
        OUT_OF_REACH,
        PROTECTED_BLOCK,
        BLOCKED_STRICT_AIR,
        BLOCKED_SOLID,
        NO_ITEM,
        ML_REJECTED,
        PLACE_FAILED,
        UNKNOWN
    }

    private static final class Entry {
        private final Block block;
        private final BlockPos target;
        private int attempts;
        private int deferrals;

        private Entry(Block block, BlockPos target) {
            this.block = block;
            this.target = target;
        }
    }
}
