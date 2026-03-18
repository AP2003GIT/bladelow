package com.bladelow.ml;

import com.bladelow.builder.PlacementJob;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Writes successful automated placements to a local JSONL dataset.
 *
 * Each row is small and append-only so it is easy to inspect by hand, feed into
 * offline training scripts, or discard if a session goes wrong.
 */
public final class BuildStyleDatasetLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path DATASET_PATH = Path.of("config", "bladelow", "ml", "placement_style_events.jsonl");

    private long samples = -1L;
    private String lastError = "";

    public synchronized void recordPlacement(
        ServerWorld world,
        ServerPlayerEntity player,
        PlacementJob job,
        BlockPos target,
        BlockState placedState,
        PlacementFeatures features
    ) {
        String jobTag = job == null ? "" : job.tag();
        recordEvent(world, player, "auto", jobTag, target, placedState, features, "");
    }

    public synchronized void recordManualPlacement(
        ServerWorld world,
        ServerPlayerEntity player,
        BlockPos target,
        BlockState placedState,
        PlacementFeatures features,
        String heldBlock
    ) {
        recordEvent(world, player, "manual", "", target, placedState, features, heldBlock);
    }

    private void recordEvent(
        ServerWorld world,
        ServerPlayerEntity player,
        String source,
        String jobTag,
        BlockPos target,
        BlockState placedState,
        PlacementFeatures features,
        String heldBlock
    ) {
        try {
            Files.createDirectories(DATASET_PATH.getParent());
            // Capture the immediate neighborhood around a successful placement.
            // This gives future models enough context to learn "what tends to be
            // placed next to what" without serializing whole chunks.
            PlacementStyleEvent event = new PlacementStyleEvent(
                Instant.now().toString(),
                source == null ? "" : source,
                world.getRegistryKey().getValue().toString(),
                world.getBiome(target).getKey().map(key -> key.getValue().toString()).orElse("minecraft:unknown"),
                jobTag == null ? "" : jobTag,
                player.getName().getString(),
                heldBlock == null ? "" : heldBlock,
                blockId(placedState),
                target.getX(),
                target.getY(),
                target.getZ(),
                features.replaceable(),
                features.support(),
                features.distance(),
                blockId(world.getBlockState(target.down())),
                blockId(world.getBlockState(target.north())),
                blockId(world.getBlockState(target.south())),
                blockId(world.getBlockState(target.east())),
                blockId(world.getBlockState(target.west())),
                blockId(world.getBlockState(target.up()))
            );
            try (Writer out = Files.newBufferedWriter(
                DATASET_PATH,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )) {
                GSON.toJson(event, out);
                out.write('\n');
            }
            if (samples >= 0) {
                samples++;
            } else {
                sampleCount();
            }
            lastError = "";
        } catch (IOException ex) {
            lastError = ex.getMessage();
        }
    }

    public synchronized String summary() {
        StringBuilder out = new StringBuilder();
        out.append("dataset[path=").append(DATASET_PATH).append(" samples=").append(sampleCount()).append("]");
        if (!lastError.isBlank()) {
            out.append(" datasetError=").append(lastError);
        }
        return out.toString();
    }

    private long sampleCount() {
        if (samples >= 0) {
            return samples;
        }
        if (!Files.exists(DATASET_PATH)) {
            samples = 0;
            return samples;
        }
        try {
            try (var lines = Files.lines(DATASET_PATH)) {
                samples = lines.count();
            }
        } catch (IOException ex) {
            lastError = ex.getMessage();
            samples = 0;
        }
        return samples;
    }

    private static String blockId(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private record PlacementStyleEvent(
        String timestamp,
        String source,
        String dimension,
        String biome,
        String job,
        String player,
        String heldBlock,
        String block,
        int x,
        int y,
        int z,
        double replaceable,
        double support,
        double distance,
        String below,
        String north,
        String south,
        String east,
        String west,
        String above
    ) {
    }
}
