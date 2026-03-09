package com.bladelow.auto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Persistent per-player queue of build goals.
 *
 * A "goal" is a (blueprint name, count) pair. The AutoPlanner works through
 * the queue one entry at a time, decrementing the count each time it queues
 * a job. Goals survive server restarts via JSON persistence.
 *
 * Thread-safety: all public methods are synchronized on the class monitor.
 */
public final class BuildGoalQueue {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BuildGoalQueue() {
    }

    // -------------------------------------------------------------------------
    // Public record
    // -------------------------------------------------------------------------

    public record Goal(String blueprintName, int remaining) {
        public Goal withRemaining(int n) {
            return new Goal(blueprintName, n);
        }
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /** Add count copies of blueprintName to the end of the queue. */
    public static synchronized void add(UUID playerId, String blueprintName, int count) {
        if (blueprintName == null || blueprintName.isBlank() || count <= 0) return;
        List<Goal> goals = load(playerId);
        // Merge with existing entry if same blueprint is already last
        if (!goals.isEmpty()) {
            Goal last = goals.get(goals.size() - 1);
            if (last.blueprintName().equals(blueprintName)) {
                goals.set(goals.size() - 1, last.withRemaining(last.remaining() + count));
                save(playerId, goals);
                return;
            }
        }
        goals.add(new Goal(blueprintName, count));
        save(playerId, goals);
    }

    /** Remove all goals for this player. */
    public static synchronized void clear(UUID playerId) {
        save(playerId, List.of());
    }

    /** Remove a specific goal by index (1-based). */
    public static synchronized boolean remove(UUID playerId, int oneBasedIndex) {
        List<Goal> goals = load(playerId);
        if (oneBasedIndex < 1 || oneBasedIndex > goals.size()) return false;
        goals.remove(oneBasedIndex - 1);
        save(playerId, goals);
        return true;
    }

    /**
     * Peek at the next goal without consuming it.
     * Returns null if the queue is empty.
     */
    public static synchronized Goal peek(UUID playerId) {
        List<Goal> goals = load(playerId);
        return goals.isEmpty() ? null : goals.get(0);
    }

    /**
     * Decrement the count of the first goal by 1.
     * Removes the goal entirely if count reaches 0.
     * Returns the goal that was consumed, or null if queue was empty.
     */
    public static synchronized Goal consume(UUID playerId) {
        List<Goal> goals = load(playerId);
        if (goals.isEmpty()) return null;
        Goal head = goals.get(0);
        if (head.remaining() <= 1) {
            goals.remove(0);
        } else {
            goals.set(0, head.withRemaining(head.remaining() - 1));
        }
        save(playerId, goals);
        return head;
    }

    /** Return a snapshot of all goals (defensive copy). */
    public static synchronized List<Goal> snapshot(UUID playerId) {
        return Collections.unmodifiableList(load(playerId));
    }

    public static synchronized boolean isEmpty(UUID playerId) {
        return load(playerId).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private static List<Goal> load(UUID playerId) {
        Path path = goalPath(playerId);
        if (!Files.exists(path)) return new ArrayList<>();
        try (Reader r = Files.newBufferedReader(path)) {
            JsonArray arr = GSON.fromJson(r, JsonArray.class);
            if (arr == null) return new ArrayList<>();
            List<Goal> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String name = obj.has("blueprint") ? obj.get("blueprint").getAsString() : null;
                int count   = obj.has("remaining")  ? obj.get("remaining").getAsInt()   : 0;
                if (name != null && !name.isBlank() && count > 0) {
                    out.add(new Goal(name, count));
                }
            }
            return out;
        } catch (IOException | RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    private static void save(UUID playerId, List<Goal> goals) {
        try {
            Path path = goalPath(playerId);
            Files.createDirectories(path.getParent());
            JsonArray arr = new JsonArray();
            for (Goal g : goals) {
                JsonObject obj = new JsonObject();
                obj.addProperty("blueprint", g.blueprintName());
                obj.addProperty("remaining", g.remaining());
                arr.add(obj);
            }
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(arr, w);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path goalPath(UUID playerId) {
        return FabricLoader.getInstance().getGameDir()
            .resolve("config/bladelow/goals/" + playerId + ".json");
    }
}
