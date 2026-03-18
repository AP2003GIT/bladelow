package com.bladelow.ml;

/**
 * Minimal numeric feature vector for placement decisions.
 */
public record PlacementFeatures(double bias, double replaceable, double support, double distance) {
}
