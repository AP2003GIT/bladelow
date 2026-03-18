package com.bladelow.ml;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Infers coarse theme/category tags from blueprint block makeup.
 *
 * This is intentionally heuristic instead of "smart" ML: the goal is to make
 * imported or weakly-tagged blueprints useful to the planner immediately while
 * we collect better training data for richer models later.
 */
public final class BlueprintAutoClassifier {
    private BlueprintAutoClassifier() {
    }

    public static Classification classify(List<String> rawBlockStates, int width, int depth, int height) {
        if (rawBlockStates == null || rawBlockStates.isEmpty()) {
            return Classification.empty();
        }

        // Count broad material families first, then derive tags from ratios and
        // footprint. This keeps the classifier cheap and robust even when block
        // states contain properties or modded variants.
        int total = 0;
        int oak = 0;
        int spruce = 0;
        int birch = 0;
        int darkOak = 0;
        int mangrove = 0;
        int cherry = 0;
        int acacia = 0;
        int jungle = 0;
        int bamboo = 0;
        int stone = 0;
        int brick = 0;
        int deepslate = 0;
        int copper = 0;
        int cloth = 0;
        int fence = 0;
        int slabOrStair = 0;
        int logs = 0;
        int glass = 0;
        int doors = 0;
        int storage = 0;
        int smithy = 0;
        int water = 0;

        for (String rawState : rawBlockStates) {
            String path = path(rawState);
            if (path.isBlank()) {
                continue;
            }
            total++;

            if (path.contains("dark_oak")) {
                darkOak++;
            } else if (path.contains("pale_oak") || path.contains("oak")) {
                oak++;
            } else if (path.contains("spruce")) {
                spruce++;
            } else if (path.contains("birch")) {
                birch++;
            } else if (path.contains("mangrove")) {
                mangrove++;
            } else if (path.contains("cherry")) {
                cherry++;
            } else if (path.contains("acacia")) {
                acacia++;
            } else if (path.contains("jungle")) {
                jungle++;
            } else if (path.contains("bamboo")) {
                bamboo++;
            }

            if (containsAny(path, "deepslate", "blackstone", "tuff")) {
                deepslate++;
                stone++;
            } else if (containsAny(path, "stone", "cobblestone", "andesite", "diorite", "granite", "sandstone", "quartz")) {
                stone++;
            }
            if (containsAny(path, "brick")) {
                brick++;
            }
            if (containsAny(path, "copper")) {
                copper++;
            }
            if (containsAny(path, "wool", "carpet", "banner", "terracotta")) {
                cloth++;
            }
            if (containsAny(path, "fence", "wall")) {
                fence++;
            }
            if (containsAny(path, "slab", "stairs")) {
                slabOrStair++;
            }
            if (containsAny(path, "log", "wood", "stem", "hyphae")) {
                logs++;
            }
            if (containsAny(path, "glass", "pane")) {
                glass++;
            }
            if (containsAny(path, "door", "trapdoor")) {
                doors++;
            }
            if (containsAny(path, "chest", "barrel", "shulker_box")) {
                storage++;
            }
            if (containsAny(path, "anvil", "blast_furnace", "furnace", "smithing_table", "grindstone")) {
                smithy++;
            }
            if (path.contains("water")) {
                water++;
            }
        }

        if (total == 0) {
            return Classification.empty();
        }

        int wood = oak + spruce + birch + darkOak + mangrove + cherry + acacia + jungle + bamboo + logs;
        int footprint = Math.max(1, width * depth);
        LinkedHashSet<String> themeTags = new LinkedHashSet<>();
        LinkedHashSet<String> tags = new LinkedHashSet<>();

        // Theme tags describe "what it feels like", while tags describe "what
        // role it probably plays in town planning".
        String dominantWood = dominantMaterial(
            oak,
            spruce,
            birch,
            darkOak,
            mangrove,
            cherry,
            acacia,
            jungle,
            bamboo
        );
        if (!dominantWood.isBlank() && share(countFor(dominantWood, oak, spruce, birch, darkOak, mangrove, cherry, acacia, jungle, bamboo), total) >= 0.10) {
            themeTags.add(dominantWood);
        }
        if (share(stone, total) >= 0.18) {
            themeTags.add("stone");
        }
        if (share(brick, total) >= 0.10) {
            themeTags.add("brick");
        }
        if (share(copper, total) >= 0.10) {
            themeTags.add("copper");
        }
        if (share(cloth, total) >= 0.10) {
            themeTags.add("cloth");
        }
        if (share(wood, total) >= 0.18 && share(stone, total) >= 0.14 && slabOrStair > 0) {
            themeTags.add("medieval");
        }

        boolean openAir = height <= 4;
        boolean compact = footprint <= 49;
        if (openAir && compact && fence >= 4 && cloth >= 2) {
            tags.add("market");
        }
        if (smithy > 0) {
            tags.add("utility");
            tags.add("smithy");
        }
        if (storage >= 3) {
            tags.add("storage");
        }
        if (water >= 2 && share(stone, total) >= 0.20 && footprint <= 81) {
            tags.add("plaza");
            tags.add("decor");
        } else if (openAir && total <= 96) {
            tags.add("decor");
        }
        if (!tags.contains("market") && doors > 0 && height >= 5 && footprint >= 16) {
            tags.add("residential");
        }
        if (footprint >= 80 && height >= 7 && share(stone, total) >= 0.20) {
            tags.add("civic");
        }
        if (glass >= 6 && !themeTags.contains("medieval")) {
            tags.add("modern");
        }

        return new Classification(List.copyOf(themeTags), List.copyOf(tags));
    }

    private static double share(int count, int total) {
        return count / (double) Math.max(1, total);
    }

    private static boolean containsAny(String path, String... needles) {
        for (String needle : needles) {
            if (path.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String path(String rawState) {
        if (rawState == null) {
            return "";
        }
        String normalized = rawState.trim().toLowerCase(Locale.ROOT);
        int props = normalized.indexOf('[');
        if (props >= 0) {
            normalized = normalized.substring(0, props);
        }
        int namespace = normalized.indexOf(':');
        if (namespace >= 0) {
            normalized = normalized.substring(namespace + 1);
        }
        return normalized;
    }

    private static String dominantMaterial(
        int oak,
        int spruce,
        int birch,
        int darkOak,
        int mangrove,
        int cherry,
        int acacia,
        int jungle,
        int bamboo
    ) {
        int best = 0;
        String name = "";
        int[] counts = {oak, spruce, birch, darkOak, mangrove, cherry, acacia, jungle, bamboo};
        String[] names = {"oak", "spruce", "birch", "dark_oak", "mangrove", "cherry", "acacia", "jungle", "bamboo"};
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > best) {
                best = counts[i];
                name = names[i];
            }
        }
        return name;
    }

    private static int countFor(
        String name,
        int oak,
        int spruce,
        int birch,
        int darkOak,
        int mangrove,
        int cherry,
        int acacia,
        int jungle,
        int bamboo
    ) {
        return switch (name) {
            case "oak" -> oak;
            case "spruce" -> spruce;
            case "birch" -> birch;
            case "dark_oak" -> darkOak;
            case "mangrove" -> mangrove;
            case "cherry" -> cherry;
            case "acacia" -> acacia;
            case "jungle" -> jungle;
            case "bamboo" -> bamboo;
            default -> 0;
        };
    }

    public record Classification(List<String> themeTags, List<String> tags) {
        public static Classification empty() {
            return new Classification(List.of(), List.of());
        }
    }
}
