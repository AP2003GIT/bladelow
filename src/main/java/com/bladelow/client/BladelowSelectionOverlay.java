package com.bladelow.client;

import com.bladelow.network.HudAction;
import com.bladelow.network.HudCommandPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.BlockPos;

/**
 * World-space particle overlay for current selection markers.
 *
 * This mirrors HUD/command selection state so the player can see the planned
 * box directly in the world while moving around.
 */
public final class BladelowSelectionOverlay {
    private static final int DEFAULT_HEIGHT = 1;

    private static final DustParticleEffect WHITE_MARK = new DustParticleEffect(0xF5F5F5, 1.0f);
    private static final DustParticleEffect BLUE_MARK = new DustParticleEffect(0x4CB8FF, 1.0f);
    private static final DustParticleEffect RED_MARK = new DustParticleEffect(0xFF6262, 1.0f);

    private static BlockPos markerA;
    private static BlockPos markerB;
    private static int markerHeight = DEFAULT_HEIGHT;
    private static int tickCooldown;

    private BladelowSelectionOverlay() {
    }

    public static void clear() {
        markerA = null;
        markerB = null;
        markerHeight = DEFAULT_HEIGHT;
    }

    public static void setMarkers(BlockPos a, BlockPos b, int height) {
        markerA = a;
        markerB = b;
        markerHeight = Math.max(1, height);
    }

    public static void setDraftMarkers(BlockPos a, BlockPos b, int heightHint) {
        markerA = a;
        markerB = b;
        if (heightHint > 0) {
            markerHeight = heightHint;
        }
    }

    public static void applyHudAction(HudCommandPayload payload) {
        if (payload == null) {
            return;
        }
        if (payload.action() == HudAction.SELECTION_CLEAR) {
            clear();
            return;
        }
        if (payload.action() == HudAction.SELECTION_MARKER_BOX && payload.args().size() >= 7) {
            // Mirror the selection marker payload so the in-world overlay stays
            // in sync with the HUD without reparsing a fake command string.
            Integer x1 = parseInt(payload.args().get(0));
            Integer y1 = parseInt(payload.args().get(1));
            Integer z1 = parseInt(payload.args().get(2));
            Integer x2 = parseInt(payload.args().get(3));
            Integer y2 = parseInt(payload.args().get(4));
            Integer z2 = parseInt(payload.args().get(5));
            Integer h = parseInt(payload.args().get(6));
            if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null || h == null || h < 1) {
                return;
            }
            setMarkers(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), h);
        }
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            return;
        }
        if (markerA == null && markerB == null) {
            return;
        }
        if (tickCooldown > 0) {
            tickCooldown--;
            return;
        }
        tickCooldown = 2;

        ClientWorld world = client.world;

        if (markerA != null) {
            spawnMarker(world, markerA, WHITE_MARK);
        }
        if (markerB != null) {
            spawnMarker(world, markerB, BLUE_MARK);
        }
        if (markerA != null && markerB != null) {
            spawnSelectionFrame(world, markerA, markerB, markerHeight);
        }
    }

    private static void spawnMarker(ClientWorld world, BlockPos pos, DustParticleEffect color) {
        world.addParticleClient(color, pos.getX() + 0.5, pos.getY() + 1.04, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        world.addParticleClient(color, pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
    }

    private static void spawnSelectionFrame(ClientWorld world, BlockPos a, BlockPos b, int height) {
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());
        int topY = minY + Math.max(1, height);

        int xSpan = Math.max(1, maxX - minX);
        int zSpan = Math.max(1, maxZ - minZ);
        int ySpan = Math.max(1, topY - minY);

        int xStep = Math.max(1, xSpan / 10);
        int zStep = Math.max(1, zSpan / 10);
        int yStep = Math.max(1, ySpan / 6);

        for (int x = minX; x <= maxX; x += xStep) {
            spawnDot(world, WHITE_MARK, x + 0.5, minY + 0.04, minZ + 0.5);
            spawnDot(world, WHITE_MARK, x + 0.5, minY + 0.04, maxZ + 0.5);
            spawnDot(world, WHITE_MARK, x + 0.5, topY + 0.04, minZ + 0.5);
            spawnDot(world, WHITE_MARK, x + 0.5, topY + 0.04, maxZ + 0.5);
        }

        for (int z = minZ; z <= maxZ; z += zStep) {
            spawnDot(world, BLUE_MARK, minX + 0.5, minY + 0.04, z + 0.5);
            spawnDot(world, BLUE_MARK, maxX + 0.5, minY + 0.04, z + 0.5);
            spawnDot(world, BLUE_MARK, minX + 0.5, topY + 0.04, z + 0.5);
            spawnDot(world, BLUE_MARK, maxX + 0.5, topY + 0.04, z + 0.5);
        }

        for (int y = minY; y <= topY; y += yStep) {
            double yy = y + 0.08;
            spawnDot(world, RED_MARK, minX + 0.5, yy, minZ + 0.5);
            spawnDot(world, RED_MARK, maxX + 0.5, yy, minZ + 0.5);
            spawnDot(world, RED_MARK, minX + 0.5, yy, maxZ + 0.5);
            spawnDot(world, RED_MARK, maxX + 0.5, yy, maxZ + 0.5);
        }
    }

    private static void spawnDot(ClientWorld world, DustParticleEffect color, double x, double y, double z) {
        world.addParticleClient(color, x, y, z, 0.0, 0.0, 0.0);
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
