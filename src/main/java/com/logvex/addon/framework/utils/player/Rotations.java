package com.logvex.addon.framework.utils.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Client-side aiming.
 *
 * <p>A rotation is applied to the local player and sent as a look packet, the action runs, and the
 * previous rotation is restored. Sending the look packet before the action matters: the server
 * validates the interaction against the rotation it last received, so a purely local
 * {@code setYaw} would be rejected or mis-aimed.
 */
public final class Rotations {
    private Rotations() {
    }

    public static void rotate(double yaw, double pitch, int priority, boolean clientSide, Runnable callback) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) {
            if (callback != null) callback.run();
            return;
        }

        float prevYaw = mc.player.getYaw();
        float prevPitch = mc.player.getPitch();

        mc.player.setYaw((float) yaw);
        mc.player.setPitch((float) pitch);
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround((float) yaw, (float) pitch, mc.player.isOnGround()));

        try {
            if (callback != null) callback.run();
        } finally {
            mc.player.setYaw(prevYaw);
            mc.player.setPitch(prevPitch);
        }
    }

    public static void rotate(double yaw, double pitch, int priority, Runnable callback) {
        rotate(yaw, pitch, priority, false, callback);
    }

    public static void rotate(double yaw, double pitch, Runnable callback) {
        rotate(yaw, pitch, 0, callback);
    }

    public static double getYaw(Vec3d pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        return mc.player.getYaw() + MathHelper.wrapDegrees((float) Math.toDegrees(
            Math.atan2(pos.getZ() - mc.player.getZ(), pos.getX() - mc.player.getX())) - 90f - mc.player.getYaw());
    }

    public static double getPitch(Vec3d pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        double diffX = pos.getX() - mc.player.getX();
        double diffY = pos.getY() - mc.player.getEyeY();
        double diffZ = pos.getZ() - mc.player.getZ();
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        return mc.player.getPitch() + MathHelper.wrapDegrees(
            (float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getPitch());
    }

    public static double getYaw(Entity entity) {
        return getYaw(entity.getPos());
    }
}
