package com.bladelow.builder;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public final class BuildProfileStore {
    private BuildProfileStore() {
    }

    public static List<String> list(MinecraftServer server) {
        Properties p = loadProps(server);
        List<String> out = new ArrayList<>();
        for (String k : p.stringPropertyNames()) {
            if (k.endsWith(".smartMove")) {
                out.add(k.substring(0, k.length() - ".smartMove".length()));
            }
        }
        if (!out.contains("builder")) {
            out.add("builder");
        }
        if (!out.contains("safe")) {
            out.add("safe");
        }
        if (!out.contains("fast")) {
            out.add("fast");
        }
        Collections.sort(out);
        return out;
    }

    public static String save(MinecraftServer server, String name) {
        String n = normalize(name);
        Properties p = loadProps(server);
        p.setProperty(n + ".smartMove", Boolean.toString(BuildRuntimeSettings.smartMoveEnabled()));
        p.setProperty(n + ".mode", BuildRuntimeSettings.moveMode().name().toLowerCase());
        p.setProperty(n + ".reach", Double.toString(BuildRuntimeSettings.reachDistance()));
        p.setProperty(n + ".strictAir", Boolean.toString(BuildRuntimeSettings.strictAirOnly()));
        p.setProperty(n + ".preview", Boolean.toString(BuildRuntimeSettings.previewBeforeBuild()));
        return storeProps(server, p, "saved profile " + n);
    }

    public static String load(MinecraftServer server, String name) {
        String n = normalize(name);
        if ("builder".equals(n)) {
            BuildRuntimeSettings.setSmartMoveEnabled(true);
            BuildRuntimeSettings.setMoveMode("walk");
            BuildRuntimeSettings.setReachDistance(4.5);
            BuildRuntimeSettings.setStrictAirOnly(false);
            BuildRuntimeSettings.setPreviewBeforeBuild(false);
            return "loaded profile builder";
        }
        if ("safe".equals(n)) {
            BuildRuntimeSettings.setSmartMoveEnabled(true);
            BuildRuntimeSettings.setMoveMode("walk");
            BuildRuntimeSettings.setReachDistance(4.0);
            BuildRuntimeSettings.setStrictAirOnly(true);
            BuildRuntimeSettings.setPreviewBeforeBuild(true);
            return "loaded profile safe";
        }
        if ("fast".equals(n)) {
            BuildRuntimeSettings.setSmartMoveEnabled(true);
            BuildRuntimeSettings.setMoveMode("teleport");
            BuildRuntimeSettings.setReachDistance(6.0);
            BuildRuntimeSettings.setStrictAirOnly(false);
            BuildRuntimeSettings.setPreviewBeforeBuild(false);
            return "loaded profile fast";
        }

        Properties p = loadProps(server);
        if (!p.containsKey(n + ".smartMove")) {
            return "profile not found: " + n;
        }

        BuildRuntimeSettings.setSmartMoveEnabled(Boolean.parseBoolean(p.getProperty(n + ".smartMove", "true")));
        BuildRuntimeSettings.setMoveMode(p.getProperty(n + ".mode", "walk"));
        try {
            BuildRuntimeSettings.setReachDistance(Double.parseDouble(p.getProperty(n + ".reach", "4.5")));
        } catch (NumberFormatException ignored) {
            BuildRuntimeSettings.setReachDistance(4.5);
        }
        BuildRuntimeSettings.setStrictAirOnly(Boolean.parseBoolean(p.getProperty(n + ".strictAir", "false")));
        BuildRuntimeSettings.setPreviewBeforeBuild(Boolean.parseBoolean(p.getProperty(n + ".preview", "false")));
        return "loaded profile " + n;
    }

    private static Path profilePath(MinecraftServer server) {
        return server.getRunDirectory().resolve("config").resolve("bladelow").resolve("profiles.properties");
    }

    private static Properties loadProps(MinecraftServer server) {
        Properties p = new Properties();
        Path path = profilePath(server);
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    p.load(r);
                }
            }
        } catch (IOException ignored) {
        }
        return p;
    }

    private static String storeProps(MinecraftServer server, Properties p, String okText) {
        Path path = profilePath(server);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                p.store(w, "Bladelow build profiles");
            }
            return okText;
        } catch (IOException ex) {
            return "profile save failed: " + ex.getMessage();
        }
    }

    private static String normalize(String input) {
        return input.trim().toLowerCase();
    }
}
