package com.bladelow.builder;

import java.util.Locale;

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

    // Baritone-inspired execution knobs: scheduler lookahead + deferred retries.
    private static boolean targetSchedulerEnabled = true;
    private static int schedulerLookahead = 14;
    private static boolean deferUnreachableTargets = true;
    private static int maxTargetDeferrals = 2;

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

    public static synchronized boolean targetSchedulerEnabled() {
        return targetSchedulerEnabled;
    }

    public static synchronized void setTargetSchedulerEnabled(boolean enabled) {
        targetSchedulerEnabled = enabled;
    }

    public static synchronized int schedulerLookahead() {
        return schedulerLookahead;
    }

    public static synchronized void setSchedulerLookahead(int lookahead) {
        schedulerLookahead = Math.max(1, Math.min(96, lookahead));
    }

    public static synchronized boolean deferUnreachableTargets() {
        return deferUnreachableTargets;
    }

    public static synchronized void setDeferUnreachableTargets(boolean enabled) {
        deferUnreachableTargets = enabled;
    }

    public static synchronized int maxTargetDeferrals() {
        return maxTargetDeferrals;
    }

    public static synchronized void setMaxTargetDeferrals(int maxDeferrals) {
        maxTargetDeferrals = Math.max(0, Math.min(8, maxDeferrals));
    }

    public static synchronized String summary() {
        return snapshot().summary();
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(
            smartMoveEnabled,
            reachDistance,
            moveMode,
            strictAirOnly,
            previewBeforeBuild,
            targetSchedulerEnabled,
            schedulerLookahead,
            deferUnreachableTargets,
            maxTargetDeferrals
        );
    }

    public record Snapshot(
        boolean smartMoveEnabled,
        double reachDistance,
        MoveMode moveMode,
        boolean strictAirOnly,
        boolean previewBeforeBuild,
        boolean targetSchedulerEnabled,
        int schedulerLookahead,
        boolean deferUnreachableTargets,
        int maxTargetDeferrals
    ) {
        public String summary() {
            return "smartMove=" + smartMoveEnabled
                + " mode=" + moveMode.name().toLowerCase(Locale.ROOT)
                + " reach=" + String.format(Locale.ROOT, "%.2f", reachDistance)
                + " strictAir=" + strictAirOnly
                + " preview=" + previewBeforeBuild
                + " scheduler=" + (targetSchedulerEnabled ? "on" : "off")
                + "(lookahead=" + schedulerLookahead + ")"
                + " defer=" + (deferUnreachableTargets ? "on" : "off")
                + "(max=" + maxTargetDeferrals + ")";
        }
    }
}
