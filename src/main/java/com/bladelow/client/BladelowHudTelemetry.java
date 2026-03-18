package com.bladelow.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side parser for Bladelow status output.
 *
 * The HUD uses this as a lightweight telemetry stream so it can display build
 * progress without needing a custom networking layer.
 */
public final class BladelowHudTelemetry {
    private static final int MAX_LOG_LINES = 24;

    private static final Pattern PROGRESS_PATTERN = Pattern.compile("progress\\s+(\\d+)/(\\d+)");
    private static final Pattern COUNTER_PATTERN = Pattern.compile("(placed|skipped|failed)=(-?\\d+)");

    private static final Deque<String> LINES = new ArrayDeque<>();

    private static int done;
    private static int total;
    private static int placed;
    private static int skipped;
    private static int failed;
    private static String latestIntent = "";

    private BladelowHudTelemetry() {
    }

    public static synchronized void recordServerMessage(String rawMessage) {
        if (rawMessage == null) {
            return;
        }

        String line = rawMessage.trim();
        if (line.isEmpty()) {
            return;
        }

        // Ignore unrelated chat so the HUD reflects only Bladelow runtime state.
        boolean isBladelowLine = line.contains("[Bladelow]");
        if (!isBladelowLine) {
            return;
        }

        line = line.replace("[Bladelow]", "").trim();
        if (line.isEmpty()) {
            return;
        }

        appendLine(line);
        parseProgress(line);
        parseIntent(line);
    }

    public static synchronized void recordLocalMessage(String localLine) {
        if (localLine == null) {
            return;
        }
        String cleaned = localLine.trim();
        if (cleaned.isEmpty()) {
            return;
        }
        appendLine("cmd " + cleaned);
    }

    public static synchronized List<String> recent(int maxLines) {
        int wanted = Math.max(0, maxLines);
        List<String> all = new ArrayList<>(LINES);
        if (all.size() <= wanted) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - wanted, all.size()));
    }

    public static synchronized ProgressSnapshot snapshot() {
        return new ProgressSnapshot(done, total, placed, skipped, failed);
    }

    public static synchronized String latestIntent() {
        return latestIntent;
    }

    private static void appendLine(String line) {
        LINES.addLast(line);
        while (LINES.size() > MAX_LOG_LINES) {
            LINES.removeFirst();
        }
    }

    private static void parseProgress(String line) {
        // Parse the same human-readable lines shown to the player so command
        // output and HUD status stay aligned.
        Matcher progress = PROGRESS_PATTERN.matcher(line.toLowerCase(Locale.ROOT));
        if (progress.find()) {
            done = parseInt(progress.group(1), done);
            total = parseInt(progress.group(2), total);
        }

        Matcher counter = COUNTER_PATTERN.matcher(line.toLowerCase(Locale.ROOT));
        while (counter.find()) {
            int value = parseInt(counter.group(2), 0);
            switch (counter.group(1)) {
                case "placed" -> placed = value;
                case "skipped" -> skipped = value;
                case "failed" -> failed = value;
                default -> {
                }
            }
        }

        if (line.contains("build complete")) {
            if (total > 0) {
                done = total;
            }
        }
    }

    private static void parseIntent(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("intent ")) {
            return;
        }
        latestIntent = line.substring("intent ".length()).trim();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public record ProgressSnapshot(int done, int total, int placed, int skipped, int failed) {
        public int percent() {
            if (total <= 0) {
                return 0;
            }
            return (int) Math.round((done * 100.0) / total);
        }
    }
}
