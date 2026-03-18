package com.bladelow.ml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Central access point for Bladelow's lightweight learning systems.
 *
 * The runtime still builds with deterministic planners, but everything that can
 * be learned locally hangs off this class so the rest of the mod can ask for:
 * placement scoring, logged build events, environment observations, style
 * references, and memory-based style hints without caring how each piece is
 * stored.
 */
public final class BladelowLearning {
    private static final PlacementModel MODEL = new PlacementModel();
    private static final BuildStyleDatasetLogger DATASET_LOGGER = new BuildStyleDatasetLogger();
    private static final EnvironmentObservationLogger ENVIRONMENT_LOGGER = new EnvironmentObservationLogger();
    private static final BuildIntentExampleLogger BUILD_INTENT_LOGGER = new BuildIntentExampleLogger();
    private static final StyleReferenceLibrary STYLE_REFERENCES = new StyleReferenceLibrary();
    private static final EnvironmentStyleMemory STYLE_MEMORY = new EnvironmentStyleMemory(STYLE_REFERENCES);
    private static final BuildIntentPredictor BUILD_INTENT_PREDICTOR = new BuildIntentPredictor();
    private static final Path MODEL_PATH = Path.of("config", "bladelow", "model.properties");

    private BladelowLearning() {
    }

    public static PlacementModel model() {
        return MODEL;
    }

    public static BuildStyleDatasetLogger datasetLogger() {
        return DATASET_LOGGER;
    }

    public static EnvironmentObservationLogger environmentLogger() {
        return ENVIRONMENT_LOGGER;
    }

    public static BuildIntentExampleLogger buildIntentLogger() {
        return BUILD_INTENT_LOGGER;
    }

    public static StyleReferenceLibrary styleReferences() {
        return STYLE_REFERENCES;
    }

    public static EnvironmentStyleMemory styleMemory() {
        return STYLE_MEMORY;
    }

    public static BuildIntentPredictor buildIntentPredictor() {
        return BUILD_INTENT_PREDICTOR;
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

    public static String summary() {
        // Keep one compact status line so HUD/commands can show the whole local
        // learning state without needing separate debug commands for every
        // subsystem.
        return MODEL.summary()
            + " " + DATASET_LOGGER.summary()
            + " " + ENVIRONMENT_LOGGER.summary()
            + " " + BUILD_INTENT_LOGGER.summary()
            + " " + STYLE_REFERENCES.summary()
            + " " + STYLE_MEMORY.summary()
            + " " + BUILD_INTENT_PREDICTOR.summary();
    }
}
