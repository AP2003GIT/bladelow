package com.bladelow.builder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildItWebService {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Pattern JSON_LINK_PATTERN = Pattern.compile("https://[^\\\"'\\s>]+\\.json");
    private static final String CATALOG_URL =
        "https://builditapp.com/wp-json/wp/v2/posts?per_page=%d&_fields=title,link";

    private static final Map<UUID, List<CatalogItem>> LAST_CATALOG = new ConcurrentHashMap<>();

    private BuildItWebService() {
    }

    public static Result syncCatalog(UUID playerId, int limit) {
        int size = Math.max(1, Math.min(50, limit));
        String url = String.format(CATALOG_URL, size);
        try {
            String body = httpGet(url);
            JsonArray arr = GSON.fromJson(body, JsonArray.class);
            List<CatalogItem> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                JsonObject titleObj = o.getAsJsonObject("title");
                String title = titleObj != null && titleObj.has("rendered") ? stripHtml(titleObj.get("rendered").getAsString()) : "Build " + (i + 1);
                String link = o.has("link") ? o.get("link").getAsString() : "";
                if (!link.isBlank()) {
                    out.add(new CatalogItem(i + 1, title, link));
                }
            }
            LAST_CATALOG.put(playerId, out);
            return Result.ok("catalog synced entries=" + out.size());
        } catch (IOException | InterruptedException | JsonParseException ex) {
            return Result.error("catalog sync failed: " + ex.getMessage());
        }
    }

    public static List<CatalogItem> catalog(UUID playerId) {
        return LAST_CATALOG.getOrDefault(playerId, List.of());
    }

    public static Result importPicked(MinecraftServer server, UUID playerId, int index, String name) {
        List<CatalogItem> items = LAST_CATALOG.get(playerId);
        if (items == null || items.isEmpty()) {
            return Result.error("no catalog loaded; run /bladeweb catalog first");
        }
        if (index < 1 || index > items.size()) {
            return Result.error("index out of range 1.." + items.size());
        }
        return importFromUrl(server, items.get(index - 1).url(), name);
    }

    public static Result importFromUrl(MinecraftServer server, String sourceUrl, String name) {
        try {
            URI uri = URI.create(sourceUrl.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!isAllowedHost(host)) {
                return Result.error("unsupported host: " + host);
            }

            String body = httpGet(sourceUrl);
            String json = tryExtractBlueprintJson(body);
            if (json == null) {
                String nested = findJsonLink(body);
                if (nested != null) {
                    URI nestedUri = URI.create(nested);
                    String nestedHost = nestedUri.getHost() == null ? "" : nestedUri.getHost().toLowerCase(Locale.ROOT);
                    if (!isAllowedHost(nestedHost)) {
                        return Result.error("nested json host not allowed: " + nestedHost);
                    }
                    json = httpGet(nested);
                }
            }
            if (json == null) {
                return Result.error("no blueprint json found in source");
            }

            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("placements")) {
                return Result.error("json missing 'placements'");
            }
            String finalName = (name == null || name.isBlank()) ? inferName(sourceUrl) : sanitizeName(name);
            root.addProperty("name", finalName);

            Path dir = server.getRunDirectory().resolve("config").resolve("bladelow").resolve("blueprints");
            Files.createDirectories(dir);
            Path out = dir.resolve(finalName + ".json");
            try (Writer writer = Files.newBufferedWriter(out)) {
                GSON.toJson(root, writer);
            }

            String reload = BlueprintLibrary.reload(server);
            return Result.ok("imported '" + finalName + "'; " + reload);
        } catch (IllegalArgumentException ex) {
            return Result.error("invalid url");
        } catch (IOException | InterruptedException | JsonParseException ex) {
            return Result.error("import failed: " + ex.getMessage());
        }
    }

    private static String tryExtractBlueprintJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.contains("\"placements\"")) {
            return trimmed;
        }
        return null;
    }

    private static String findJsonLink(String html) {
        Matcher m = JSON_LINK_PATTERN.matcher(html);
        if (m.find()) {
            return m.group();
        }
        return null;
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
            || hostMatches(host, "githubusercontent.com")
            || hostMatches(host, "gist.github.com");
    }

    private static boolean hostMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "BladelowBuilder/0.1.0 (+https://builditapp.com/)")
            .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("http " + res.statusCode());
        }
        return res.body();
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
