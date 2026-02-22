package com.bladelow.builder;

import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.UUID;

public class PlacementJob {
    private final UUID playerId;
    private final RegistryKey<World> worldKey;
    private final List<Block> blocks;
    private final List<BlockPos> targets;
    private final String tag;
    private final int[] attempts;

    private int cursor;
    private int placed;
    private int skipped;
    private int failed;
    private int moved;
    private int alreadyPlaced;
    private int blocked;
    private int protectedBlocked;
    private int noReach;
    private int mlRejected;
    private double totalScore;
    private int ticks;

    public PlacementJob(UUID playerId, RegistryKey<World> worldKey, List<Block> blocks, List<BlockPos> targets, String tag) {
        if (blocks.size() != targets.size()) {
            throw new IllegalArgumentException("blocks and targets size mismatch");
        }
        this.playerId = playerId;
        this.worldKey = worldKey;
        this.blocks = List.copyOf(blocks);
        this.targets = List.copyOf(targets);
        this.attempts = new int[targets.size()];
        this.tag = tag;
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

    public int totalTargets() {
        return targets.size();
    }

    public int cursor() {
        return cursor;
    }

    public BlockPos currentTarget() {
        return targets.get(cursor);
    }

    public Block currentBlock() {
        return blocks.get(cursor);
    }

    public BlockPos targetAt(int idx) {
        return targets.get(idx);
    }

    public void advance() {
        cursor++;
    }

    public int currentAttempts() {
        return attempts[cursor];
    }

    public int incrementCurrentAttempts() {
        attempts[cursor]++;
        return attempts[cursor];
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
        return cursor >= targets.size();
    }

    public String progressSummary() {
        return "[Bladelow] " + tag + " progress " + cursor + "/" + totalTargets()
            + " placed=" + placed
            + " skipped=" + skipped
            + " failed=" + failed
            + " moved=" + moved;
    }

    public String completionSummary() {
        double avgScore = totalScore / Math.max(1, totalTargets());
        return "[Bladelow] " + tag
            + " targets=" + totalTargets()
            + " placed=" + placed
            + " skipped=" + skipped
            + " failed=" + failed
            + " moved=" + moved
            + " already=" + alreadyPlaced
            + " blocked=" + blocked
            + " protected=" + protectedBlocked
            + " noReach=" + noReach
            + " mlSkip=" + mlRejected
            + " avgScore=" + String.format("%.3f", avgScore)
            + " " + BuildRuntimeSettings.summary();
    }
}
