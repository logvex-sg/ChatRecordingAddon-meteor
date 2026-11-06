package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.Rotation;
import com.logvex.addon.schematicdropper.core.RotationMath;
import com.logvex.addon.schematicdropper.core.RotationPort;
import com.logvex.addon.framework.utils.player.Rotations;

/**
 * Rotation adapter built on the addon's {@link Rotations} helper.
 *
 * <p>{@code Rotations} queues a yaw/pitch pair and restores the client's own rotation right after
 * the movement packet is sent. The player's {@code getYaw()} therefore never reflects the queued
 * rotation, so this class keeps its own interpolated state and exposes it through {@link #apply}.
 */
public final class ClientRotationPort implements RotationPort {
    private static final int PRIORITY = 50;

    private Rotation current;

    @Override
    public Rotation apply(Rotation target, boolean instant, float speed) {
        if (target == null) return current;

        if (current == null || instant) {
            current = target;
        } else {
            float yaw = RotationMath.stepToward(current.yaw(), target.yaw(), speed);
            float pitch = RotationMath.stepToward(current.pitch(), target.pitch(), speed);
            current = new Rotation(yaw, pitch);
        }

        Rotations.rotate(current.yaw(), current.pitch(), PRIORITY, true, null);
        return current;
    }

    @Override
    public void restore(Rotation rotation) {
        current = rotation;
        if (rotation != null) {
            Rotations.rotate(rotation.yaw(), rotation.pitch(), PRIORITY, true, null);
        }
    }

    @Override
    public void reset() {
        current = null;
    }

    /** Last rotation this port aimed with, or {@code null} when it has not rotated yet. */
    public Rotation current() {
        return current;
    }
}
