package com.bladelow.ml;

import java.util.Properties;

/**
 * Lightweight online model for deciding whether to attempt a placement now.
 *
 * It behaves like a tiny perceptron so Bladelow can adapt from live build
 * outcomes without dragging in a heavy ML runtime.
 */
public final class PlacementModel {
    private static final double DEFAULT_BIAS_WEIGHT = 0.20;
    private static final double DEFAULT_REPLACEABLE_WEIGHT = 0.65;
    private static final double DEFAULT_SUPPORT_WEIGHT = 0.35;
    private static final double DEFAULT_DISTANCE_WEIGHT = -0.30;
    private static final double DEFAULT_THRESHOLD = 0.10;
    private static final double DEFAULT_LEARNING_RATE = 0.05;
    private static final double WEIGHT_CLAMP = 5.0;

    private double biasWeight = DEFAULT_BIAS_WEIGHT;
    private double replaceableWeight = DEFAULT_REPLACEABLE_WEIGHT;
    private double supportWeight = DEFAULT_SUPPORT_WEIGHT;
    private double distanceWeight = DEFAULT_DISTANCE_WEIGHT;

    private double threshold = DEFAULT_THRESHOLD;
    private double learningRate = DEFAULT_LEARNING_RATE;

    private long updates;

    public synchronized double score(PlacementFeatures f) {
        return (f.bias() * biasWeight)
            + (f.replaceable() * replaceableWeight)
            + (f.support() * supportWeight)
            + (f.distance() * distanceWeight);
    }

    public synchronized boolean shouldPlace(PlacementFeatures f) {
        return score(f) >= threshold;
    }

    public synchronized void train(PlacementFeatures f, boolean success) {
        // Online update: reward successful patterns and penalize repeated
        // failures while keeping the model simple enough for live gameplay.
        int label = success ? 1 : -1;
        int predicted = score(f) >= threshold ? 1 : -1;
        int error = label - predicted;
        if (error == 0) {
            return;
        }

        biasWeight = clamp(biasWeight + learningRate * error * f.bias(), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        replaceableWeight = clamp(replaceableWeight + learningRate * error * f.replaceable(), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        supportWeight = clamp(supportWeight + learningRate * error * f.support(), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        distanceWeight = clamp(distanceWeight + learningRate * error * f.distance(), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        updates++;
    }

    public synchronized void reset() {
        biasWeight = DEFAULT_BIAS_WEIGHT;
        replaceableWeight = DEFAULT_REPLACEABLE_WEIGHT;
        supportWeight = DEFAULT_SUPPORT_WEIGHT;
        distanceWeight = DEFAULT_DISTANCE_WEIGHT;
        threshold = DEFAULT_THRESHOLD;
        learningRate = DEFAULT_LEARNING_RATE;
        updates = 0;
    }

    public synchronized void configure(double threshold, double learningRate) {
        this.threshold = threshold;
        this.learningRate = learningRate;
    }

    public synchronized Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("biasWeight", Double.toString(biasWeight));
        p.setProperty("replaceableWeight", Double.toString(replaceableWeight));
        p.setProperty("supportWeight", Double.toString(supportWeight));
        p.setProperty("distanceWeight", Double.toString(distanceWeight));
        p.setProperty("threshold", Double.toString(threshold));
        p.setProperty("learningRate", Double.toString(learningRate));
        p.setProperty("updates", Long.toString(updates));
        return p;
    }

    public synchronized void fromProperties(Properties p) {
        biasWeight = clamp(parseDouble(p, "biasWeight", DEFAULT_BIAS_WEIGHT), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        replaceableWeight = clamp(parseDouble(p, "replaceableWeight", DEFAULT_REPLACEABLE_WEIGHT), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        supportWeight = clamp(parseDouble(p, "supportWeight", DEFAULT_SUPPORT_WEIGHT), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        distanceWeight = clamp(parseDouble(p, "distanceWeight", DEFAULT_DISTANCE_WEIGHT), -WEIGHT_CLAMP, WEIGHT_CLAMP);
        threshold = parseDouble(p, "threshold", DEFAULT_THRESHOLD);
        learningRate = parseDouble(p, "learningRate", DEFAULT_LEARNING_RATE);
        updates = parseLong(p, "updates", 0L);
    }

    public synchronized String summary() {
        return String.format(
            "threshold=%.3f lr=%.3f weights[bias=%.3f replaceable=%.3f support=%.3f distance=%.3f] updates=%d",
            threshold,
            learningRate,
            biasWeight,
            replaceableWeight,
            supportWeight,
            distanceWeight,
            updates
        );
    }

    private static double parseDouble(Properties p, String key, double fallback) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long parseLong(Properties p, String key, long fallback) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
