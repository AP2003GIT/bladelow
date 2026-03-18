package com.bladelow.ml;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Loads optional image references that describe a desired town/build style.
 *
 * Bladelow does not try to reconstruct exact geometry from these images yet.
 * Instead, it treats them as lightweight style anchors and extracts cheap image
 * features plus optional hand-authored labels/tags from sidecar JSON files.
 */
public final class StyleReferenceLibrary {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path IMAGE_DIR = Path.of("config", "bladelow", "ml", "style_refs");

    private long fingerprint = Long.MIN_VALUE;
    private String lastError = "";
    private List<StyleReference> cache = List.of();

    public synchronized List<StyleReference> references() {
        refreshIfNeeded();
        return cache;
    }

    public synchronized String summary() {
        refreshIfNeeded();
        StringBuilder out = new StringBuilder();
        out.append("refs[path=").append(IMAGE_DIR).append(" images=").append(cache.size()).append("]");
        if (!lastError.isBlank()) {
            out.append(" refError=").append(lastError);
        }
        return out.toString();
    }

    private void refreshIfNeeded() {
        try {
            Files.createDirectories(IMAGE_DIR);
            long currentFingerprint = fingerprint(IMAGE_DIR);
            if (currentFingerprint == fingerprint) {
                return;
            }
            fingerprint = currentFingerprint;
            cache = loadReferences();
            lastError = "";
        } catch (IOException ex) {
            lastError = ex.getMessage();
        }
    }

    private List<StyleReference> loadReferences() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(IMAGE_DIR)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(StyleReferenceLibrary::isImageFile)
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        }

        List<StyleReference> references = new ArrayList<>();
        for (Path file : files) {
            StyleReference reference = loadReference(file);
            if (reference != null) {
                references.add(reference);
            }
        }
        return List.copyOf(references);
    }

    private StyleReference loadReference(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            StyleReferenceMeta meta = loadMeta(imagePath);
            // The feature extractor stays intentionally simple for now so the
            // reference library is cheap to refresh at runtime.
            StyleImageFeatures features = extract(image);
            return new StyleReference(
                imagePath.getFileName().toString(),
                features.width(),
                features.height(),
                features.averageRed(),
                features.averageGreen(),
                features.averageBlue(),
                features.brightness(),
                features.saturation(),
                meta == null ? "" : normalize(meta.label),
                meta == null ? List.of() : normalizeTags(meta.tags)
            );
        } catch (IOException ex) {
            lastError = ex.getMessage();
            return null;
        }
    }

    private static StyleReferenceMeta loadMeta(Path imagePath) {
        String fileName = imagePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        Path metaPath = imagePath.getParent().resolve(base + ".json");
        if (!Files.exists(metaPath)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(metaPath)) {
            return GSON.fromJson(reader, StyleReferenceMeta.class);
        } catch (IOException ex) {
            return null;
        }
    }

    private static StyleImageFeatures extract(BufferedImage image) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        double brightness = 0.0;
        double saturation = 0.0;
        int count = 0;

        // Sample at a capped grid instead of every pixel so large screenshots do
        // not slow the game down just because they were dropped into style_refs.
        int stepX = Math.max(1, image.getWidth() / 64);
        int stepY = Math.max(1, image.getHeight() / 64);
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                red += r;
                green += g;
                blue += b;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                brightness += max / 255.0;
                saturation += max == 0 ? 0.0 : (max - min) / (double) max;
                count++;
            }
        }

        if (count <= 0) {
            return new StyleImageFeatures(image.getWidth(), image.getHeight(), 0, 0, 0, 0.0, 0.0);
        }
        return new StyleImageFeatures(
            image.getWidth(),
            image.getHeight(),
            (int) Math.round(red / (double) count),
            (int) Math.round(green / (double) count),
            (int) Math.round(blue / (double) count),
            brightness / count,
            saturation / count
        );
    }

    private static long fingerprint(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toMillis() ^ path.getFileName().toString().hashCode();
                    } catch (IOException ex) {
                        return path.getFileName().toString().hashCode();
                    }
                })
                .sum();
        }
    }

    private static boolean isImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tag : tags) {
            String normalized = normalize(tag);
            if (!normalized.isBlank() && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }

    public record StyleReference(
        String fileName,
        int width,
        int height,
        int averageRed,
        int averageGreen,
        int averageBlue,
        double brightness,
        double saturation,
        String label,
        List<String> tags
    ) {
    }

    private record StyleImageFeatures(
        int width,
        int height,
        int averageRed,
        int averageGreen,
        int averageBlue,
        double brightness,
        double saturation
    ) {
    }

    private static final class StyleReferenceMeta {
        String label;
        List<String> tags;
    }
}
