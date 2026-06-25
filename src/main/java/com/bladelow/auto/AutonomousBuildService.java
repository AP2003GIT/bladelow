package com.bladelow.auto;

import com.bladelow.builder.BlueprintLibrary;
import com.bladelow.builder.IntentStructurePlanner;
import com.bladelow.builder.SelectionState;
import com.bladelow.builder.TownZoneStore;
import com.bladelow.builder.WorldContextMemoryStore;
import com.bladelow.builder.WorldPerceptionSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade for the autonomous builder loop.
 */
public final class AutonomousBuildService {
    private static final int MAX_BLUEPRINT_CAPTURE_BLOCKS = 262144;

    private AutonomousBuildService() {
    }

    public static CaptureResult captureSelectedStructure(
        MinecraftServer server,
        ServerWorld world,
        ServerPlayerEntity player,
        String name,
        int height,
        boolean includeAir
    ) {
        if (server == null || world == null || player == null) {
            return CaptureResult.error("capture context unavailable");
        }
        if (name == null || name.isBlank()) {
            return CaptureResult.error("blueprint name required");
        }
        if (height < 1 || height > 256) {
            return CaptureResult.error("height must be 1..256");
        }

        List<BlockPos> base = SelectionState.snapshot(player.getUuid(), world.getRegistryKey());
        if (base.isEmpty()) {
            return CaptureResult.error("selection is empty; mark an area in the HUD first");
        }

        Bounds bounds = Bounds.from(base);
        long volume = bounds.baseArea() * ((long) height + 1L);
        if (volume > MAX_BLUEPRINT_CAPTURE_BLOCKS) {
            return CaptureResult.error("capture volume too large (" + volume + " blocks). limit=" + MAX_BLUEPRINT_CAPTURE_BLOCKS);
        }

        BlueprintLibrary.SaveResult save = BlueprintLibrary.captureSelectionAsBlueprint(
            server,
            world,
            name,
            base,
            height,
            includeAir
        );
        if (!save.ok()) {
            return CaptureResult.error(save.message());
        }

        BlueprintLibrary.select(player.getUuid(), name);
        return CaptureResult.ok(
            save.message(),
            bounds.minX(),
            bounds.minY(),
            bounds.minZ(),
            bounds.maxX(),
            bounds.maxY() + height,
            bounds.maxZ(),
            volume
        );
    }

    public static PerceptionResult perceiveSelectedArea(
        MinecraftServer server,
        ServerWorld world,
        ServerPlayerEntity player
    ) {
        if (server == null || world == null || player == null) {
            return PerceptionResult.error("perception context unavailable");
        }
        List<BlockPos> base = SelectionState.snapshot(player.getUuid(), world.getRegistryKey());
        if (base.isEmpty()) {
            return PerceptionResult.error("selection is empty; mark an area in the HUD first");
        }

        Bounds bounds = Bounds.from(base);
        WorldPerceptionSnapshot snapshot = WorldPerceptionSnapshot.scan(
            world,
            new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
            new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()),
            TownZoneStore.snapshot(player.getUuid(), world.getRegistryKey())
        );
        WorldContextMemoryStore.SaveResult save = WorldContextMemoryStore.append(server, snapshot);
        if (!save.ok()) {
            return PerceptionResult.error(save.message());
        }
        return PerceptionResult.ok(snapshot.compactSummary() + "; " + save.message(), snapshot);
    }

    public static IntentStructurePlanner.GeneratedBuild generateCityStructure(
        ServerWorld world,
        ServerPlayerEntity player,
        BlockPos from,
        BlockPos to,
        List<String> args,
        int variant
    ) {
        if (world == null || player == null || from == null || to == null) {
            return IntentStructurePlanner.GeneratedBuild.error("invalid generation context");
        }
        String plotLabel = args == null || args.isEmpty() ? "" : args.get(0);
        List<String> slotOverrides = new ArrayList<>();
        if (args != null) {
            for (int i = 1; i < Math.min(args.size(), 4); i++) {
                String token = args.get(i);
                if (!token.equals("-")) {
                    slotOverrides.add(token);
                }
            }
        }
        return IntentStructurePlanner.planSelection(
            world,
            player.getUuid(),
            from,
            to,
            plotLabel,
            slotOverrides,
            variant
        );
    }

    public record CaptureResult(
        boolean ok,
        String message,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        long volume
    ) {
        public static CaptureResult ok(
            String message,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            long volume
        ) {
            return new CaptureResult(true, message, minX, minY, minZ, maxX, maxY, maxZ, volume);
        }

        public static CaptureResult error(String message) {
            return new CaptureResult(false, message, 0, 0, 0, 0, 0, 0, 0L);
        }
    }

    public record PerceptionResult(boolean ok, String message, WorldPerceptionSnapshot snapshot) {
        public static PerceptionResult ok(String message, WorldPerceptionSnapshot snapshot) {
            return new PerceptionResult(true, message, snapshot);
        }

        public static PerceptionResult error(String message) {
            return new PerceptionResult(false, message, null);
        }
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds from(List<BlockPos> points) {
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
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        long baseArea() {
            return ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
        }
    }
}
