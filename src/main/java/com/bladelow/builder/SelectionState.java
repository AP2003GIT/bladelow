package com.bladelow.builder;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player temporary selection buffer.
 *
 * This is the working set of points used by commands and HUD tools before a
 * selection is promoted into markers, zones, or exported structures.
 */
public final class SelectionState {
    // Plain HashMap is correct here: all public methods are synchronized on the
    // class monitor, so no concurrent access can occur. ConcurrentHashMap was
    // redundant and would suggest a misleading mixed-locking model.
    private static final Map<SelectionKey, PlayerSelection> SELECTIONS = new HashMap<>();

    private SelectionState() {
    }

    public static synchronized boolean add(UUID playerId, RegistryKey<World> worldKey, BlockPos pos) {
        PlayerSelection selection = selectionFor(playerId, worldKey);
        BlockPos immutable = pos.toImmutable();
        if (selection.selectedSet.add(immutable)) {
            selection.selectedList.add(immutable);
            return true;
        }
        return false;
    }

    public static synchronized int size(UUID playerId, RegistryKey<World> worldKey) {
        return selectionFor(playerId, worldKey).selectedList.size();
    }

    public static synchronized boolean remove(UUID playerId, RegistryKey<World> worldKey, BlockPos pos) {
        PlayerSelection selection = selectionFor(playerId, worldKey);
        BlockPos key = pos.toImmutable();
        if (!selection.selectedSet.remove(key)) {
            return false;
        }
        selection.selectedList.remove(key);
        return true;
    }

    public static synchronized BlockPos popLast(UUID playerId, RegistryKey<World> worldKey) {
        PlayerSelection selection = selectionFor(playerId, worldKey);
        if (selection.selectedList.isEmpty()) {
            return null;
        }
        BlockPos last = selection.selectedList.remove(selection.selectedList.size() - 1);
        selection.selectedSet.remove(last);
        return last;
    }

    public static synchronized void clear(UUID playerId, RegistryKey<World> worldKey) {
        SELECTIONS.remove(selectionKey(playerId, worldKey));
    }

    public static synchronized List<BlockPos> snapshot(UUID playerId, RegistryKey<World> worldKey) {
        return List.copyOf(selectionFor(playerId, worldKey).selectedList);
    }

    private static PlayerSelection selectionFor(UUID playerId, RegistryKey<World> worldKey) {
        return SELECTIONS.computeIfAbsent(selectionKey(playerId, worldKey), ignored -> new PlayerSelection());
    }

    private static SelectionKey selectionKey(UUID playerId, RegistryKey<World> worldKey) {
        return new SelectionKey(playerId, worldKey.getValue().toString());
    }

    private static final class PlayerSelection {
        private final Set<BlockPos> selectedSet = new HashSet<>();
        private final List<BlockPos> selectedList = new ArrayList<>();
    }

    private record SelectionKey(UUID playerId, String worldId) {
    }
}
