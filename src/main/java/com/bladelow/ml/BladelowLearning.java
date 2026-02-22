package com.bladelow.ml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class BladelowLearning {
    private static final PlacementModel MODEL = new PlacementModel();
    private static final Path MODEL_PATH = Path.of("config", "bladelow", "model.properties");

    private BladelowLearning() {
    }

    public static PlacementModel model() {
        return MODEL;
    }

    public static Path modelPath() {
        return MODEL_PATH;
    }

    public static synchronized String save() {
        try {
            Files.createDirectories(MODEL_PATH.getParent());
            Properties p = MODEL.toProperties();
            try (OutputStream out = Files.newOutputStream(MODEL_PATH)) {
                p.store(out, "Bladelow ML model state");
            }
            return "saved to " + MODEL_PATH;
        } catch (IOException ex) {
            return "save failed: " + ex.getMessage();
        }
    }

    public static synchronized String load() {
        if (!Files.exists(MODEL_PATH)) {
            return "no saved model at " + MODEL_PATH;
        }

        try (InputStream in = Files.newInputStream(MODEL_PATH)) {
            Properties p = new Properties();
            p.load(in);
            MODEL.fromProperties(p);
            return "loaded from " + MODEL_PATH;
        } catch (IOException ex) {
            return "load failed: " + ex.getMessage();
        }
    }
}
