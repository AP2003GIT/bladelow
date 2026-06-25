package com.bladelow.builder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WorldContextMemoryStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path MEMORY_PATH = Path.of("config", "bladelow", "memory", "world_context.jsonl");

    private WorldContextMemoryStore() {
    }

    public static SaveResult append(MinecraftServer server, WorldPerceptionSnapshot snapshot) {
        if (server == null) {
            return SaveResult.error("server is unavailable");
        }
        if (snapshot == null) {
            return SaveResult.error("world perception snapshot is empty");
        }
        Path path = server.getRunDirectory().resolve(MEMORY_PATH);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )) {
                GSON.toJson(snapshot, writer);
                writer.write('\n');
            }
            return SaveResult.ok("saved world context memory");
        } catch (IOException ex) {
            return SaveResult.error("world context save failed: " + ex.getMessage());
        }
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
