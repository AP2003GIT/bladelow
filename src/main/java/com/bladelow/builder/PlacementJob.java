package com.bladelow.builder;

import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKey;
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
    private double totalScore;
    private int ticks;

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
    }

    public void recordSkipped() {
        skipped++;
    }

    public void recordFailed() {
        failed++;
    }

    public void recordMoved() {
        moved++;
    }

    public void recordAlreadyPlaced() {
        alreadyPlaced++;
    }

    public void recordBlocked() {
        blocked++;
    }

    public void recordProtectedBlocked() {
        protectedBlocked++;
    }

    public void recordNoReach() {
        noReach++;
    }

    public void recordMlRejected() {
        mlRejected++;
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
            + " placed=" + placed
            + " skipped=" + skipped
            + " failed=" + failed
            + " moved=" + moved
            + " deferred=" + deferred
            + " replan=" + reprioritized
            + current;
    }

    public String completionSummary() {
        double avgScore = totalScore / Math.max(1, totalTargets());
        double noReachPct = (noReach * 100.0) / Math.max(1, totalTargets());
        return "[Bladelow] " + tag
            + " targets=" + totalTargets()
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
            + " avgScore=" + String.format(Locale.ROOT, "%.3f", avgScore)
            + " " + runtimeSettings.summary();
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
