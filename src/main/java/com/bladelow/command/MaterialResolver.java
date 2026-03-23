package com.bladelow.command;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Handles inventory stock checking, material fallback selection,
 * and feasibility reporting for placement jobs.
 *
 * Originally split out from the old command layer so material resolution stays
 * reusable for the HUD action service and other server-side entry points.
 */
public final class MaterialResolver {

    private MaterialResolver() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public record Resolution(
        List<BlockState> blockStates,
        int substitutions,
        int unresolved,
        int required,
        int covered,
        double feasibilityPercent,
        String summary
    ) {
    }

    public static Resolution resolve(ServerPlayerEntity player, List<BlockState> requested) {
        if (requested == null || requested.isEmpty()) {
            return new Resolution(List.of(), 0, 0, 0, 0, 100.0, "");
        }
        if (player.getAbilities().creativeMode) {
            int required = countRequired(requested);
            return new Resolution(requested, 0, 0, required, required, 100.0, "");
        }

        Map<Block, Integer> stock = inventoryBlockStock(player);
        if (stock.isEmpty()) {
            int missing = countRequired(requested);
            String summary = missing > 0
                ? "material auto-map missing=" + missing + " (no placeable block items found in inventory)"
                : "";
            double feasibility = missing <= 0 ? 100.0 : 0.0;
            return new Resolution(requested, 0, missing, missing, 0, feasibility, summary);
        }

        Set<Block> preferredPalette = new LinkedHashSet<>();
        for (BlockState state : requested) {
            if (state != null) preferredPalette.add(state.getBlock());
        }

        List<BlockState> resolved = new ArrayList<>(requested.size());
        Map<String, Integer> substitutions = new LinkedHashMap<>();
        int substituted = 0;
        int unresolved = 0;
        int required = 0;

        for (BlockState desiredState : requested) {
            Block desired = desiredState == null ? Blocks.AIR : desiredState.getBlock();
            if (desired != null && desired.asItem() != Items.AIR) required++;

            if (hasStock(stock, desired)) {
                consumeStock(stock, desired);
                resolved.add(desiredState);
                continue;
            }

            Block fallback = chooseFallback(desired, stock, preferredPalette);
            if (fallback != null) {
                consumeStock(stock, fallback);
                resolved.add(fallback == desired ? desiredState : fallback.getDefaultState());
                substituted++;
                substitutions.merge(shortId(desired) + "->" + shortId(fallback), 1, Integer::sum);
                continue;
            }

            resolved.add(desiredState);
            if (desired != null && desired.asItem() != Items.AIR) unresolved++;
        }

        if (substituted == 0 && unresolved == 0) {
            return new Resolution(resolved, 0, 0, required, required, 100.0, "");
        }

        StringBuilder summary = new StringBuilder("material auto-map");
        if (substituted > 0) summary.append(" substitutions=").append(substituted);
        if (unresolved > 0) summary.append(" missing=").append(unresolved);
        if (!substitutions.isEmpty()) {
            summary.append(" map=");
            int shown = 0;
            for (Map.Entry<String, Integer> entry : substitutions.entrySet()) {
                if (shown > 0) summary.append(", ");
                summary.append(entry.getKey()).append("(").append(entry.getValue()).append(")");
                if (++shown >= 4) break;
            }
            if (substitutions.size() > 4) summary.append(", +").append(substitutions.size() - 4).append(" more");
        }
        int covered = Math.max(0, required - unresolved);
        double feasibility = required <= 0 ? 100.0 : (covered * 100.0) / required;
        summary.append(" feasible=").append(String.format(Locale.ROOT, "%.1f%%", feasibility));
        return new Resolution(resolved, substituted, unresolved, required, covered, feasibility, summary.toString());
    }

    // -------------------------------------------------------------------------
    // Block spec parsing (shared utility used by multiple command modules)
    // -------------------------------------------------------------------------

    /**
     * Parses a comma-separated block id spec (1–3 ids) into a list of Blocks.
     * Returns an empty list and sends an error to source on failure.
     */
    public static List<Block> parseBlockSpec(String blockSpec, net.minecraft.server.command.ServerCommandSource source) {
        String[] tokens = blockSpec.split(",");
        if (tokens.length < 1 || tokens.length > 3) {
            source.sendError(blueText("Block list must have 1 to 3 ids (comma-separated)."));
            return List.of();
        }
        List<Block> blocks = new ArrayList<>();
        for (String token : tokens) {
            String blockIdText = token.trim();
            Identifier id = Identifier.tryParse(blockIdText);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                source.sendError(blueText("Invalid block id: " + blockIdText));
                return List.of();
            }
            blocks.add(Registries.BLOCK.get(id));
        }
        return blocks;
    }

    // -------------------------------------------------------------------------
    // Package-private helpers used by PlacementPipeline
    // -------------------------------------------------------------------------

    static Map<Block, Integer> inventoryBlockStock(ServerPlayerEntity player) {
        Map<Block, Integer> stock = new HashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (!(item instanceof BlockItem blockItem)) continue;
            Block block = blockItem.getBlock();
            if (block == null || block.asItem() == Items.AIR) continue;
            stock.merge(block, stack.getCount(), Integer::sum);
        }
        return stock;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static int countRequired(List<BlockState> states) {
        int count = 0;
        for (BlockState state : states) {
            Block block = state == null ? Blocks.AIR : state.getBlock();
            if (block != null && block.asItem() != Items.AIR) count++;
        }
        return count;
    }

    private static Block chooseFallback(Block desired, Map<Block, Integer> stock, Set<Block> preferred) {
        if (stock == null || stock.isEmpty()) return null;
        String desiredGroup = materialGroup(desired);
        Block sameGroup = bestStocked(stock, c -> c != desired && materialGroup(c).equals(desiredGroup));
        if (sameGroup != null) return sameGroup;
        Block fromPalette = bestStocked(stock, c -> c != desired && preferred.contains(c));
        if (fromPalette != null) return fromPalette;
        return bestStocked(stock, c -> c != desired);
    }

    private static Block bestStocked(Map<Block, Integer> stock, Predicate<Block> predicate) {
        Block best = null;
        int bestCount = 0;
        String bestId = "";
        for (Map.Entry<Block, Integer> entry : stock.entrySet()) {
            Block candidate = entry.getKey();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (candidate == null || count <= 0 || !predicate.test(candidate)) continue;
            String id = blockId(candidate);
            if (best == null || count > bestCount || (count == bestCount && id.compareTo(bestId) < 0)) {
                best = candidate;
                bestCount = count;
                bestId = id;
            }
        }
        return best;
    }

    private static boolean hasStock(Map<Block, Integer> stock, Block block) {
        if (block == null || block.asItem() == Items.AIR) return false;
        return stock.getOrDefault(block, 0) > 0;
    }

    private static void consumeStock(Map<Block, Integer> stock, Block block) {
        if (stock == null || block == null) return;
        Integer count = stock.get(block);
        if (count == null || count <= 1) stock.remove(block);
        else stock.put(block, count - 1);
    }

    static String materialGroup(Block block) {
        if (block == null) return "unknown";
        String path = Registries.BLOCK.getId(block).getPath().toLowerCase();
        if (path.contains("planks"))   return "planks";
        if (path.contains("log") || path.contains("wood")) return "wood";
        if (path.contains("stone") || path.contains("cobble") || path.contains("deepslate")) return "stone";
        if (path.contains("brick"))    return "brick";
        if (path.contains("glass"))    return "glass";
        if (path.contains("concrete")) return "concrete";
        if (path.contains("terracotta")) return "terracotta";
        if (path.contains("slab"))     return "slab";
        if (path.contains("stairs"))   return "stairs";
        int split = path.indexOf('_');
        return split > 0 ? path.substring(0, split) : path;
    }

    static String shortId(Block block) {
        String id = blockId(block);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    static String blockId(Block block) {
        if (block == null) return "minecraft:air";
        Identifier id = Registries.BLOCK.getId(block);
        return id == null ? "minecraft:air" : id.toString();
    }

    private static net.minecraft.text.Text blueText(String msg) {
        return net.minecraft.text.Text.literal(msg)
            .formatted(net.minecraft.util.Formatting.AQUA);
    }
}
