package com.bladelow.builder;

public final class BuildRuntimeSettings {
    public enum MoveMode {
        TELEPORT,
        WALK,
        AUTO
    }

    private static boolean smartMoveEnabled = true;
    private static double reachDistance = 4.5;
    private static MoveMode moveMode = MoveMode.WALK;
    private static boolean strictAirOnly = false;
    private static boolean previewBeforeBuild = false;

    private BuildRuntimeSettings() {
    }

    public static synchronized boolean smartMoveEnabled() {
        return smartMoveEnabled;
    }

    public static synchronized void setSmartMoveEnabled(boolean enabled) {
        smartMoveEnabled = enabled;
    }

    public static synchronized double reachDistance() {
        return reachDistance;
    }

    public static synchronized void setReachDistance(double distance) {
        reachDistance = Math.max(2.0, Math.min(8.0, distance));
    }

    public static synchronized MoveMode moveMode() {
        return moveMode;
    }

    public static synchronized void setMoveMode(MoveMode mode) {
        moveMode = mode;
    }

    public static synchronized boolean setMoveMode(String mode) {
        if ("teleport".equalsIgnoreCase(mode)) {
            moveMode = MoveMode.TELEPORT;
            return true;
        }
        if ("walk".equalsIgnoreCase(mode)) {
            moveMode = MoveMode.WALK;
            return true;
        }
        if ("auto".equalsIgnoreCase(mode)) {
            moveMode = MoveMode.AUTO;
            return true;
        }
        return false;
    }

    public static synchronized String summary() {
        return "smartMove=" + smartMoveEnabled
            + " mode=" + moveMode.name().toLowerCase()
            + " reach=" + String.format("%.2f", reachDistance)
            + " strictAir=" + strictAirOnly
            + " preview=" + previewBeforeBuild;
    }

    public static synchronized boolean strictAirOnly() {
        return strictAirOnly;
    }

    public static synchronized void setStrictAirOnly(boolean strict) {
        strictAirOnly = strict;
    }

    public static synchronized boolean previewBeforeBuild() {
        return previewBeforeBuild;
    }

    public static synchronized void setPreviewBeforeBuild(boolean enabled) {
        previewBeforeBuild = enabled;
    }
}
