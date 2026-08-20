package com.bladelow.builder;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Neutral planning artifact between generation/memory and execution.
 */
public final class BuildTaskGraph {
    private final String id;
    private final List<Task> tasks;
    private final int dependencyEdges;
    private final boolean supportFirstOrdered;

    private BuildTaskGraph(String id, List<Task> tasks, int dependencyEdges, boolean supportFirstOrdered) {
        this.id = normalizeId(id);
        this.tasks = List.copyOf(tasks);
        this.dependencyEdges = dependencyEdges;
        this.supportFirstOrdered = supportFirstOrdered;
    }

    public static BuildTaskGraph fromPlacements(
        String id,
        List<BlockState> states,
        List<BlockPos> targets,
        ServerPlayerEntity player
    ) {
        if (states == null || targets == null || states.size() != targets.size() || targets.isEmpty()) {
            return new BuildTaskGraph(id, List.of(), 0, false);
        }

        Map<BlockPos, Integer> indexByPos = new HashMap<>(targets.size() * 2);
        for (int i = 0; i < targets.size(); i++) {
            indexByPos.putIfAbsent(targets.get(i), i);
        }

        int edges = 0;
        for (BlockPos pos : targets) {
            if (indexByPos.containsKey(pos.down())) {
                edges++;
            }
        }

        List<Integer> order = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            order.add(i);
        }
        boolean supportFirst = edges > 0 && targets.size() > 1;
        if (supportFirst) {
            double px = player == null ? 0.0 : player.getX();
            double py = player == null ? 0.0 : player.getY();
            double pz = player == null ? 0.0 : player.getZ();
            Collections.sort(order, Comparator
                .comparingInt((Integer i) -> targets.get(i).getY())
                .thenComparingDouble(i -> targets.get(i).getSquaredDistance(px, py, pz))
                .thenComparingInt(i -> targets.get(i).getX())
                .thenComparingInt(i -> targets.get(i).getZ()));
        }

        List<Task> tasks = new ArrayList<>(order.size());
        for (int taskIndex = 0; taskIndex < order.size(); taskIndex++) {
            int sourceIndex = order.get(taskIndex);
            BlockPos target = targets.get(sourceIndex);
            tasks.add(new Task(
                taskIndex,
                sourceIndex,
                states.get(sourceIndex),
                target,
                indexByPos.containsKey(target.down())
            ));
        }
        return new BuildTaskGraph(id, tasks, edges, supportFirst);
    }

    public OrderedPlacements orderedPlacements() {
        List<BlockState> states = new ArrayList<>(tasks.size());
        List<BlockPos> targets = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            states.add(task.state());
            targets.add(task.target());
        }
        return new OrderedPlacements(List.copyOf(states), List.copyOf(targets));
    }

    public String id() {
        return id;
    }

    public int taskCount() {
        return tasks.size();
    }

    public int dependencyEdges() {
        return dependencyEdges;
    }

    public boolean supportFirstOrdered() {
        return supportFirstOrdered;
    }

    public String summary() {
        return "graph=" + id
            + " tasks=" + taskCount()
            + " deps=" + dependencyEdges
            + " order=" + (supportFirstOrdered ? "support-first" : "path-first");
    }

    private static String normalizeId(String input) {
        if (input == null || input.isBlank()) {
            return "build";
        }
        return input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]+", "_");
    }

    public record OrderedPlacements(List<BlockState> states, List<BlockPos> targets) {
    }
//todo add notes to graph loop
    public record Task(
        int taskIndex,
        int sourceIndex,
        BlockState state,
        BlockPos target,
        boolean dependsOnSupportBelow
    ) {
    }
}
