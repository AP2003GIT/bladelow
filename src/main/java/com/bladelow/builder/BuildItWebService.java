package com.bladelow.builder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildItWebService {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final Pattern JSON_LINK_PATTERN =
        Pattern.compile("https?://[^\\\"'\\s>]+\\.json(?:\\?[^\\\"'\\s>]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_JSON_LINK_PATTERN =
        Pattern.compile("https?:\\\\/\\\\/[^\\\"'\\s>]+\\.json(?:\\?[^\\\"'\\s>]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE_JSON_LINK_PATTERN =
        Pattern.compile("(?:href|src)\\s*=\\s*[\\\"']([^\\\"']+\\.json(?:\\?[^\\\"']*)?)[\\\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_BLOCK_PATTERN =
        Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CODE_BLOCK_PATTERN =
        Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final int MAX_HTTP_RETRIES = 3;
    private static final int MAX_LINK_IMPORT_ATTEMPTS = 8;

    private static final String[] CATALOG_URLS = {
        "https://builditapp.com/wp-json/wp/v2/posts?per_page=%d&_fields=title,link",
        "https://www.builditapp.com/wp-json/wp/v2/posts?per_page=%d&_fields=title,link",
        "https://builditapp.com/wp-json/wp/v2/pages?per_page=%d&_fields=title,link",
        "https://www.builditapp.com/wp-json/wp/v2/pages?per_page=%d&_fields=title,link"
    };

    private static final Map<UUID, List<CatalogItem>> LAST_CATALOG = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_CATALOG_ENTRIES = 100;

    private BuildItWebService() {
    }

    public static Result syncCatalog(UUID playerId, int limit) {
        int size = Math.max(1, Math.min(50, limit));
        List<CatalogItem> cached = catalog(playerId);
        StringBuilder reasons = new StringBuilder();

        for (String catalogUrl : CATALOG_URLS) {
            String url = String.format(catalogUrl, size);
            try {
                List<CatalogItem> out = fetchCatalog(url);
                if (out.isEmpty()) {
                    if (!reasons.isEmpty()) {
                        reasons.append("; ");
                    }
                    reasons.append("empty ").append(url);
                    continue;
                }
                LAST_CATALOG.put(playerId, out);
                saveCatalogCache(playerId, out);
                return Result.ok("catalog synced entries=" + out.size());
            } catch (IOException | InterruptedException | JsonParseException ex) {
                if (!reasons.isEmpty()) {
                    reasons.append("; ");
                }
                reasons.append(url).append(" -> ").append(ex.getMessage());
            }
        }

        if (!cached.isEmpty()) {
            return Result.ok("catalog sync failed, using cached entries=" + cached.size());
        }
        if (reasons.isEmpty()) {
            return Result.error("catalog sync failed: no entries returned");
        }
        return Result.error("catalog sync failed: " + reasons);
    }

    public static List<CatalogItem> catalog(UUID playerId) {
        List<CatalogItem> inMemory = LAST_CATALOG.get(playerId);
        if (inMemory != null && !inMemory.isEmpty()) {
            return inMemory;
        }
        List<CatalogItem> cached = loadCatalogCache(playerId);
        if (!cached.isEmpty()) {
            LAST_CATALOG.put(playerId, cached);
        }
        return cached;
    }

    public static Result importPicked(MinecraftServer server, UUID playerId, int index, String name) {
        List<CatalogItem> items = catalog(playerId);
        if (items == null || items.isEmpty()) {
            return Result.error("no catalog loaded; run /bladeweb catalog first");
        }
        if (index < 1 || index > items.size()) {
            return Result.error("index out of range 1.." + items.size());
        }
        return importFromUrl(server, items.get(index - 1).url(), name);
    }

    private static Path catalogCachePath(UUID playerId) {
        return Path.of(System.getProperty("user.home"), ".bladelow", "catalog-cache", playerId + ".json");
    }

    private static void saveCatalogCache(UUID playerId, List<CatalogItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            Path cachePath = catalogCachePath(playerId);
            Files.createDirectories(cachePath.getParent());

            List<CatalogItem> trimmed = new ArrayList<>(Math.min(items.size(), MAX_CACHED_CATALOG_ENTRIES));
            for (int i = 0; i < items.size() && i < MAX_CACHED_CATALOG_ENTRIES; i++) {
                CatalogItem item = items.get(i);
                if (item == null || item.url() == null || item.url().isBlank()) {
                    continue;
                }
                String title = item.title() == null || item.title().isBlank() ? ("Build " + (trimmed.size() + 1)) : item.title();
                trimmed.add(new CatalogItem(trimmed.size() + 1, title, item.url()));
            }
            try (Writer writer = Files.newBufferedWriter(cachePath)) {
                GSON.toJson(trimmed, writer);
            }
        } catch (IOException ignored) {
            // Cache write failure should not break gameplay commands.
        }
    }

    private static List<CatalogItem> loadCatalogCache(UUID playerId) {
        Path cachePath = catalogCachePath(playerId);
        if (!Files.exists(cachePath)) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(cachePath)) {
            JsonArray arr = GSON.fromJson(reader, JsonArray.class);
            if (arr == null || arr.isEmpty()) {
                return List.of();
            }

            List<CatalogItem> out = new ArrayList<>();
            for (int i = 0; i < arr.size() && out.size() < MAX_CACHED_CATALOG_ENTRIES; i++) {
                JsonElement el = arr.get(i);
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                String title = obj.has("title") ? obj.get("title").getAsString().trim() : "";
                String url = obj.has("url") ? obj.get("url").getAsString().trim() : "";
                if (url.isBlank()) {
                    continue;
                }
                if (title.isBlank()) {
                    title = "Build " + (out.size() + 1);
                }
                out.add(new CatalogItem(out.size() + 1, title, url));
            }
            return out;
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
    }

    public static Result importFromUrl(MinecraftServer server, String sourceUrl, String name) {
        try {
            URI source = normalizeInputUri(sourceUrl);
            String host = hostOf(source);
            if (!isAllowedHost(host)) {
                return Result.error("unsupported host: " + host);
            }

            String body = httpGet(source.toString());
            String json = tryExtractBlueprintJson(body);
            if (json == null) {
                List<String> nestedLinks = findJsonLinks(body, source);
                int attempts = 0;
                for (String nested : nestedLinks) {
                    if (attempts++ >= MAX_LINK_IMPORT_ATTEMPTS) {
                        break;
                    }
                    URI nestedUri;
                    try {
                        nestedUri = normalizeInputUri(nested);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    String nestedHost = hostOf(nestedUri);
                    if (!isAllowedHost(nestedHost)) {
                        continue;
                    }
                    String nestedBody = httpGet(nestedUri.toString());
                    json = tryExtractBlueprintJson(nestedBody);
                    if (json != null) {
                        break;
                    }
                }
            }
            if (json == null) {
                return Result.error("no blueprint json found in source");
            }

            JsonObject root = parseBlueprintObject(json);
            if (root == null || !hasPlacements(root)) {
                return Result.error("json missing 'placements'");
            }
            String finalName = (name == null || name.isBlank()) ? inferName(sourceUrl) : sanitizeName(name);
            JsonObject normalized = normalizeImportedBlueprint(root, finalName, sourceUrl);
            if (normalized == null || !hasPlacements(normalized)) {
                return Result.error("blueprint normalization failed (no valid placements)");
            }

            Path dir = server.getRunDirectory().resolve("config").resolve("bladelow").resolve("blueprints");
            Files.createDirectories(dir);
            Path out = dir.resolve(finalName + ".json");
            try (Writer writer = Files.newBufferedWriter(out)) {
                GSON.toJson(normalized, writer);
            }

            String reload = BlueprintLibrary.reload(server);
            return Result.ok("imported '" + finalName + "'; " + reload);
        } catch (IllegalArgumentException ex) {
            return Result.error("invalid url");
        } catch (IOException | InterruptedException | JsonParseException ex) {
            return Result.error("import failed: " + ex.getMessage());
        }
    }

    private static List<CatalogItem> fetchCatalog(String url) throws IOException, InterruptedException {
        String body = httpGet(url);
        JsonArray arr = GSON.fromJson(body, JsonArray.class);
        if (arr == null) {
            return List.of();
        }

        Set<String> seenLinks = new LinkedHashSet<>();
        List<CatalogItem> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            JsonObject titleObj = o.getAsJsonObject("title");
            String title = titleObj != null && titleObj.has("rendered")
                ? stripHtml(titleObj.get("rendered").getAsString())
                : "Build " + (out.size() + 1);
            String link = o.has("link") ? o.get("link").getAsString().trim() : "";
            if (link.isBlank() || !seenLinks.add(link)) {
                continue;
            }
            out.add(new CatalogItem(out.size() + 1, title, link));
        }

        return out;
    }

    private static String tryExtractBlueprintJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(raw.trim());
        candidates.add(unescapeHtml(raw).trim());

        Matcher codeBlock = CODE_BLOCK_PATTERN.matcher(raw);
        while (codeBlock.find()) {
            candidates.add(codeBlock.group(1));
        }

        Matcher scripts = SCRIPT_BLOCK_PATTERN.matcher(raw);
        while (scripts.find()) {
            candidates.add(scripts.group(1));
        }

        candidates.addAll(findPlacementSnippets(raw));

        for (String candidate : candidates) {
            String normalized = tryParseBlueprintJson(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static List<String> findJsonLinks(String html, URI baseUri) {
        LinkedHashSet<String> links = new LinkedHashSet<>();

        Matcher absolute = JSON_LINK_PATTERN.matcher(html);
        while (absolute.find()) {
            String cleaned = cleanupLinkCandidate(absolute.group());
            if (!cleaned.isBlank()) {
                links.add(cleaned);
            }
        }

        Matcher escaped = ESCAPED_JSON_LINK_PATTERN.matcher(html);
        while (escaped.find()) {
            String cleaned = cleanupLinkCandidate(escaped.group().replace("\\/", "/"));
            if (!cleaned.isBlank()) {
                links.add(cleaned);
            }
        }

        Matcher relative = RELATIVE_JSON_LINK_PATTERN.matcher(html);
        while (relative.find()) {
            String value = cleanupLinkCandidate(relative.group(1));
            if (value.isBlank()) {
                continue;
            }
            try {
                URI resolved = baseUri.resolve(value);
                links.add(resolved.toString());
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed values and continue.
            }
        }

        return new ArrayList<>(links);
    }

    private static String tryParseBlueprintJson(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        JsonObject obj = parseBlueprintObject(candidate);
        if (obj == null || !hasPlacements(obj)) {
            return null;
        }
        return GSON.toJson(obj);
    }

    private static JsonObject parseBlueprintObject(String jsonText) {
        try {
            JsonElement parsed = GSON.fromJson(jsonText.trim(), JsonElement.class);
            return findBlueprintObject(parsed, 0);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject findBlueprintObject(JsonElement element, int depth) {
        if (element == null || element.isJsonNull() || depth > 10) {
            return null;
        }

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (hasPlacements(obj)) {
                return obj;
            }
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                JsonObject nested = findBlueprintObject(e.getValue(), depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            int max = Math.min(arr.size(), 96);
            for (int i = 0; i < max; i++) {
                JsonObject nested = findBlueprintObject(arr.get(i), depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private static boolean hasPlacements(JsonObject obj) {
        return obj != null && obj.has("placements") && obj.get("placements").isJsonArray();
    }

    private static JsonObject normalizeImportedBlueprint(JsonObject root, String finalName, String sourceUrl) {
        JsonArray rawPlacements = root.getAsJsonArray("placements");
        List<NormalizedPlacement> placements = extractNormalizedPlacements(rawPlacements);
        if (placements.isEmpty()) {
            return null;
        }

        JsonObject out = new JsonObject();
        out.addProperty("schema", "bladelow-blueprint-v2");
        out.addProperty("name", finalName);

        JsonArray placementJson = new JsonArray();
        LinkedHashMap<String, Integer> paletteCounts = new LinkedHashMap<>();
        for (NormalizedPlacement placement : placements) {
            JsonObject p = new JsonObject();
            p.addProperty("x", placement.x());
            p.addProperty("y", placement.y());
            p.addProperty("z", placement.z());
            p.addProperty("block", placement.blockId());
            placementJson.add(p);
            paletteCounts.merge(placement.blockId(), 1, Integer::sum);
        }
        out.add("placements", placementJson);

        JsonArray palette = new JsonArray();
        for (Map.Entry<String, Integer> entry : paletteCounts.entrySet()) {
            JsonObject p = new JsonObject();
            p.addProperty("block", entry.getKey());
            p.addProperty("count", entry.getValue());
            palette.add(p);
        }
        out.add("palette", palette);
        out.add("palette_map", inferRolePalette(placements, paletteCounts));

        JsonObject meta = new JsonObject();
        meta.addProperty("source", sourceUrl);
        meta.addProperty("placements", placements.size());
        int maxX = placements.stream().mapToInt(NormalizedPlacement::x).max().orElse(0);
        int maxY = placements.stream().mapToInt(NormalizedPlacement::y).max().orElse(0);
        int maxZ = placements.stream().mapToInt(NormalizedPlacement::z).max().orElse(0);
        meta.addProperty("size", (maxX + 1) + "x" + (maxY + 1) + "x" + (maxZ + 1));
        out.add("meta", meta);
        return out;
    }

    private static List<NormalizedPlacement> extractNormalizedPlacements(JsonArray rawPlacements) {
        if (rawPlacements == null || rawPlacements.isEmpty()) {
            return List.of();
        }
        List<NormalizedPlacement> raw = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (JsonElement element : rawPlacements) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            Integer x = parseJsonInt(obj.get("x"));
            Integer y = parseJsonInt(obj.get("y"));
            Integer z = parseJsonInt(obj.get("z"));
            if (x == null || y == null || z == null) {
                continue;
            }
            String blockId = normalizeBlockId(obj.has("block") ? obj.get("block").getAsString() : "");
            if (blockId == null) {
                continue;
            }
            raw.add(new NormalizedPlacement(x, y, z, blockId));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
        }
        if (raw.isEmpty()) {
            return List.of();
        }

        List<NormalizedPlacement> normalized = new ArrayList<>(raw.size());
        for (NormalizedPlacement placement : raw) {
            normalized.add(new NormalizedPlacement(
                placement.x() - minX,
                placement.y() - minY,
                placement.z() - minZ,
                placement.blockId()
            ));
        }
        normalized.sort(
            Comparator.comparingInt(NormalizedPlacement::y)
                .thenComparingInt(NormalizedPlacement::x)
                .thenComparingInt(NormalizedPlacement::z)
                .thenComparing(NormalizedPlacement::blockId)
        );
        return normalized;
    }

    private static JsonObject inferRolePalette(List<NormalizedPlacement> placements, LinkedHashMap<String, Integer> paletteCounts) {
        JsonObject roleMap = new JsonObject();
        if (placements.isEmpty()) {
            return roleMap;
        }
        int minX = placements.stream().mapToInt(NormalizedPlacement::x).min().orElse(0);
        int maxX = placements.stream().mapToInt(NormalizedPlacement::x).max().orElse(0);
        int minY = placements.stream().mapToInt(NormalizedPlacement::y).min().orElse(0);
        int maxY = placements.stream().mapToInt(NormalizedPlacement::y).max().orElse(0);
        int minZ = placements.stream().mapToInt(NormalizedPlacement::z).min().orElse(0);
        int maxZ = placements.stream().mapToInt(NormalizedPlacement::z).max().orElse(0);

        boolean hasInterior2d = (maxX - minX) >= 2 && (maxZ - minZ) >= 2;
        boolean hasHeight = (maxY - minY) >= 1;

        Map<String, Integer> wallCounts = new LinkedHashMap<>();
        Map<String, Integer> floorCounts = new LinkedHashMap<>();
        Map<String, Integer> detailCounts = new LinkedHashMap<>();
        for (NormalizedPlacement placement : placements) {
            boolean edge2d = placement.x() == minX || placement.x() == maxX || placement.z() == minZ || placement.z() == maxZ;
            BuildRole role = classifyRole(placement, minY, maxY, edge2d, hasInterior2d, hasHeight);
            switch (role) {
                case WALL -> wallCounts.merge(placement.blockId(), 1, Integer::sum);
                case FLOOR -> floorCounts.merge(placement.blockId(), 1, Integer::sum);
                case DETAIL -> detailCounts.merge(placement.blockId(), 1, Integer::sum);
            }
        }

        String fallback = paletteCounts.keySet().stream().findFirst().orElse("minecraft:stone");
        String wall = dominantBlock(wallCounts, fallback);
        String floor = dominantBlock(floorCounts, wall);
        String detail = dominantBlock(detailCounts, floor);

        roleMap.addProperty("wall", wall);
        roleMap.addProperty("floor", floor);
        roleMap.addProperty("detail", detail);
        return roleMap;
    }

    private static BuildRole classifyRole(
        NormalizedPlacement placement,
        int minY,
        int maxY,
        boolean edge2d,
        boolean hasInterior2d,
        boolean hasHeight
    ) {
        if (!hasHeight) {
            if (!edge2d && hasInterior2d) {
                return BuildRole.FLOOR;
            }
            return BuildRole.WALL;
        }
        boolean floor = placement.y() == minY;
        boolean top = placement.y() == maxY;
        if (floor && !edge2d && hasInterior2d) {
            return BuildRole.FLOOR;
        }
        if (top && !edge2d) {
            return BuildRole.DETAIL;
        }
        if (edge2d) {
            return BuildRole.WALL;
        }
        if (floor) {
            return BuildRole.FLOOR;
        }
        if (top) {
            return BuildRole.DETAIL;
        }
        return BuildRole.WALL;
    }

    private static String dominantBlock(Map<String, Integer> counts, String fallback) {
        String best = fallback;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    private static Integer parseJsonInt(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String normalizeBlockId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (value.isBlank()) {
            return null;
        }
        if (!value.contains(":")) {
            value = "minecraft:" + value;
        }
        Identifier id = Identifier.tryParse(value);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        return id.toString();
    }

    private enum BuildRole {
        WALL,
        FLOOR,
        DETAIL
    }

    private record NormalizedPlacement(int x, int y, int z, String blockId) {
    }

    private static List<String> findPlacementSnippets(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        int from = 0;
        int hits = 0;

        while (hits < 10) {
            int idx = lower.indexOf("\"placements\"", from);
            if (idx < 0) {
                break;
            }
            String snippet = extractBalancedObject(raw, idx);
            if (snippet != null) {
                out.add(snippet);
            }
            from = idx + 12;
            hits++;
        }

        return out;
    }

    private static String extractBalancedObject(String text, int pivot) {
        for (int start = pivot; start >= 0; start--) {
            if (text.charAt(start) != '{') {
                continue;
            }
            int end = findMatchingBrace(text, start);
            if (end > pivot) {
                return text.substring(start, end + 1);
            }
        }
        return null;
    }

    private static int findMatchingBrace(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private static String cleanupLinkCandidate(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = unescapeHtml(value).trim();
        while (!cleaned.isEmpty()) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if (last == '"' || last == '\'' || last == '>' || last == ')' || last == ',' || last == ';') {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
                continue;
            }
            break;
        }
        return cleaned;
    }

    private static URI normalizeInputUri(String sourceUrl) {
        String trimmed = sourceUrl == null ? "" : sourceUrl.trim();
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            trimmed = "https://" + trimmed;
        }
        return URI.create(trimmed);
    }

    private static String hostOf(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String unescapeHtml(String v) {
        return v.replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
    }

    private static String inferName(String source) {
        String cleaned = source.toLowerCase(Locale.ROOT)
            .replace("https://", "")
            .replace("http://", "")
            .replaceAll("[^a-z0-9]+", "_");
        return sanitizeName("web_" + cleaned);
    }

    private static String sanitizeName(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
        if (n.length() > 48) {
            n = n.substring(0, 48);
        }
        if (n.isBlank()) {
            n = "web_blueprint";
        }
        return n;
    }

    private static boolean isAllowedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return hostMatches(host, "builditapp.com")
            || hostMatches(host, "github.com")
            || hostMatches(host, "githubusercontent.com")
            || hostMatches(host, "raw.githubusercontent.com")
            || hostMatches(host, "gist.github.com")
            || hostMatches(host, "gist.githubusercontent.com");
    }

    private static boolean hostMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_HTTP_RETRIES; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "BladelowBuilder/0.1.0 (+https://builditapp.com/)")
                    .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 200 && res.statusCode() < 300) {
                    return res.body();
                }
                if (isRetryableStatus(res.statusCode()) && attempt < MAX_HTTP_RETRIES) {
                    pauseBeforeRetry(attempt);
                    continue;
                }
                throw new IOException("http " + res.statusCode());
            } catch (IOException ex) {
                lastError = ex;
                if (attempt >= MAX_HTTP_RETRIES) {
                    throw ex;
                }
                pauseBeforeRetry(attempt);
            }
        }
        throw lastError == null ? new IOException("request failed") : lastError;
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static void pauseBeforeRetry(int attempt) throws InterruptedException {
        Thread.sleep(250L * attempt);
    }

    private static String stripHtml(String v) {
        return v.replaceAll("<[^>]+>", "").trim();
    }

    public record CatalogItem(int index, String title, String url) {
    }

    public record Result(boolean ok, String message) {
        public static Result ok(String m) {
            return new Result(true, m);
        }

        public static Result error(String m) {
            return new Result(false, m);
        }
    }
}
