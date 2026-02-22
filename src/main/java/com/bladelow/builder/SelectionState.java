package com.bladelow.builder;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SelectionState {
    private static final Set<BlockPos> SELECTED_SET = new HashSet<>();
    private static final List<BlockPos> SELECTED_LIST = new ArrayList<>();

    private SelectionState() {
    }

    public static synchronized boolean add(BlockPos pos) {
        if (SELECTED_SET.add(pos.toImmutable())) {
            SELECTED_LIST.add(pos.toImmutable());
            return true;
        }
        return false;
    }

    public static synchronized int size() {
        return SELECTED_LIST.size();
    }

    public static synchronized void clear() {
        SELECTED_SET.clear();
        SELECTED_LIST.clear();
    }

    public static synchronized List<BlockPos> snapshot() {
        return List.copyOf(SELECTED_LIST);
    }
}
