package com.bladelow.builder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class StructureMemoryStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private StructureMemoryStore() {
    }

    public static SaveResult save(MinecraftServer server, StructureSnapshot snapshot) {
        if (server == null) {
            return SaveResult.error("server is unavailable");
        }
        if (snapshot == null || snapshot.blocks().isEmpty()) {
            return SaveResult.error("structure snapshot is empty");
        }
        String fileName = safeFileName(snapshot.name());
        if (fileName.isBlank()) {
            return SaveResult.error("invalid structure snapshot name");
        }

        Path dir = memoryDir(server);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName + ".json");
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(snapshot, writer);
            }
            return SaveResult.ok("saved structure memory '" + fileName + "' blocks=" + snapshot.blockCount());
        } catch (IOException ex) {
            return SaveResult.error("structure memory save failed: " + ex.getMessage());
        }
    }

    private static Path memoryDir(MinecraftServer server) {
        return server.getRunDirectory()
            .resolve("config")
            .resolve("bladelow")
            .resolve("memory")
            .resolve("structures");
    }

    private static String safeFileName(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }

    public record SaveResult(boolean ok, String message) {
        public static SaveResult ok(String message) {
            return new SaveResult(true, message);
        }

        public static SaveResult error(String message) {
            return new SaveResult(false, message);
        }
    }
}
