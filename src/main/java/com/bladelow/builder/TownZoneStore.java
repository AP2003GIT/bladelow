package com.bladelow.builder;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TownZoneStore {
    private static final Map<ZoneKey, List<Zone>> ZONES = new ConcurrentHashMap<>();

    private TownZoneStore() {
    }

    public static synchronized ZoneResult setBox(UUID playerId, RegistryKey<World> worldKey, String type, BlockPos from, BlockPos to) {
        String normalized = normalizeType(type);
        if (normalized.isBlank()) {
            return ZoneResult.error("district type must be " + TownDistrictType.idsCsv());
        }
        if (from == null || to == null) {
            return ZoneResult.error("zone box is invalid");
        }

        Zone zone = Zone.of(normalized, from, to);
        ZoneKey key = zoneKey(playerId, worldKey);
        List<Zone> existing = new ArrayList<>(ZONES.getOrDefault(key, List.of()));
        boolean replaced = false;
        for (int i = 0; i < existing.size(); i++) {
            Zone current = existing.get(i);
            if (current.sameBounds(zone)) {
                existing.set(i, zone);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            existing.add(zone);
        }
        ZONES.put(key, List.copyOf(existing));
        return ZoneResult.ok((replaced ? "updated" : "saved") + " zone " + zone.summary());
    }

    public static synchronized ZoneResult setSelection(UUID playerId, RegistryKey<World> worldKey, String type, List<BlockPos> points) {
        if (points == null || points.isEmpty()) {
            return ZoneResult.error("selection is empty; use #bladeselect markerbox first");
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
        return setBox(playerId, worldKey, type, new BlockPos(minX, 0, minZ), new BlockPos(maxX, 0, maxZ));
    }

    public static synchronized List<Zone> snapshot(UUID playerId, RegistryKey<World> worldKey) {
        return List.copyOf(ZONES.getOrDefault(zoneKey(playerId, worldKey), List.of()));
    }

    public static synchronized int clear(UUID playerId, RegistryKey<World> worldKey) {
        List<Zone> removed = ZONES.remove(zoneKey(playerId, worldKey));
        return removed == null ? 0 : removed.size();
    }

    public static synchronized int clear(UUID playerId, RegistryKey<World> worldKey, String type) {
        String normalized = normalizeType(type);
        if (normalized.isBlank()) {
            return 0;
        }
        ZoneKey key = zoneKey(playerId, worldKey);
        List<Zone> existing = new ArrayList<>(ZONES.getOrDefault(key, List.of()));
        int before = existing.size();
        existing.removeIf(zone -> zone.type().equals(normalized));
        if (existing.isEmpty()) {
            ZONES.remove(key);
        } else {
            ZONES.put(key, List.copyOf(existing));
        }
        return before - existing.size();
    }

    public static Map<String, Integer> summarizeByType(List<Zone> zones) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (TownDistrictType type : TownDistrictType.values()) {
            counts.put(type.id(), 0);
        }
        if (zones == null) {
            return counts;
        }
        for (Zone zone : zones) {
            counts.merge(zone.type(), 1, Integer::sum);
        }
        counts.entrySet().removeIf(entry -> entry.getValue() == 0);
        return counts;
    }

    public static String normalizeType(String type) {
        return TownDistrictType.normalize(type);
    }

    private static ZoneKey zoneKey(UUID playerId, RegistryKey<World> worldKey) {
        return new ZoneKey(playerId, worldKey.getValue().toString());
    }

    public record Zone(String type, int minX, int maxX, int minZ, int maxZ) {
        public static Zone of(String type, BlockPos from, BlockPos to) {
            return new Zone(
                normalizeType(type),
                Math.min(from.getX(), to.getX()),
                Math.max(from.getX(), to.getX()),
                Math.min(from.getZ(), to.getZ()),
                Math.max(from.getZ(), to.getZ())
            );
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean intersects(int otherMinX, int otherMaxX, int otherMinZ, int otherMaxZ) {
            return maxX >= otherMinX && minX <= otherMaxX && maxZ >= otherMinZ && minZ <= otherMaxZ;
        }

        public int area() {
            return Math.max(1, (maxX - minX + 1) * (maxZ - minZ + 1));
        }

        public boolean sameBounds(Zone other) {
            return other != null
                && minX == other.minX
                && maxX == other.maxX
                && minZ == other.minZ
                && maxZ == other.maxZ;
        }

        public String summary() {
            return type + " (" + minX + "," + minZ + ")->(" + maxX + "," + maxZ + ")";
        }
    }

    public record ZoneResult(boolean ok, String message) {
        public static ZoneResult ok(String message) {
            return new ZoneResult(true, message);
        }

        public static ZoneResult error(String message) {
            return new ZoneResult(false, message);
        }
    }

    private record ZoneKey(UUID playerId, String worldId) {
    }
}
