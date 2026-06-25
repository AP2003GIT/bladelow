package com.bladelow.builder;

import com.bladelow.ml.BuildIntent;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Reusable perception artifact for a selected world area.
 */
public record WorldPerceptionSnapshot(
    String createdAt,
    String worldId,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ,
    int terrainMinY,
    int terrainMaxY,
    int terrainAverageY,
    int nearbyStructures,
    String primaryTheme,
    String secondaryTheme,
    int styleSamples,
    double averageStructureWidth,
    double averageStructureDepth,
    double averageStructureHeight,
    String intentArchetype,
    String intentSizeClass,
    int intentFloors,
    String intentRoofFamily,
    String intentPaletteProfile,
    String intentDetailDensity,
    String intentPrimaryTheme,
    String intentSecondaryTheme,
    double intentConfidence,
    String summary
) {
    public static WorldPerceptionSnapshot scan(
        ServerWorld world,
        BlockPos from,
        BlockPos to,
        List<TownZoneStore.Zone> zones
    ) {
        if (world == null || from == null || to == null) {
            throw new IllegalArgumentException("invalid perception bounds");
        }
        BlockPos min = new BlockPos(
            Math.min(from.getX(), to.getX()),
            Math.min(from.getY(), to.getY()),
            Math.min(from.getZ(), to.getZ())
        );
        BlockPos max = new BlockPos(
            Math.max(from.getX(), to.getX()),
            Math.max(from.getY(), to.getY()),
            Math.max(from.getZ(), to.getZ())
        );
        BuildSiteScan scan = BuildSiteAnalyzer.scan(world, min, max, min.getY(), Set.of());
        TownPlanner.IntentSuggestion suggestion = TownPlanner.suggestBuildIntent(world, min, max, zones == null ? List.of() : zones);
        BuildIntent intent = suggestion.ok() ? suggestion.intent() : BuildIntent.NONE;
        SiteStyleProfile style = scan.styleProfile() == null ? SiteStyleProfile.NONE : scan.styleProfile();
        return new WorldPerceptionSnapshot(
            Instant.now().toString(),
            world.getRegistryKey().getValue().toString(),
            min.getX(),
            min.getY(),
            min.getZ(),
            max.getX(),
            max.getY(),
            max.getZ(),
            scan.terrainMinY(),
            scan.terrainMaxY(),
            scan.terrainAverageY(),
            scan.nearbyStructures() == null ? 0 : scan.nearbyStructures().size(),
            style.primaryTheme(),
            style.secondaryTheme(),
            style.samples(),
            style.averageWidth(),
            style.averageDepth(),
            style.averageHeight(),
            intent.primaryArchetype(),
            intent.sizeClass(),
            intent.floors(),
            intent.roofFamily(),
            intent.paletteProfile(),
            intent.detailDensity(),
            intent.primaryTheme(),
            intent.secondaryTheme(),
            intent.confidence(),
            suggestion.ok() ? suggestion.message() : scan.summary()
        );
    }

    public String compactSummary() {
        return "perception area="
            + (maxX - minX + 1) + "x" + (maxZ - minZ + 1)
            + " terrain=" + terrainMinY + ".." + terrainMaxY
            + " nearby=" + nearbyStructures
            + " style=" + (primaryTheme == null || primaryTheme.isBlank() ? "none" : primaryTheme)
            + " intent=" + (intentArchetype == null || intentArchetype.isBlank() ? "none" : intentArchetype);
    }
}
