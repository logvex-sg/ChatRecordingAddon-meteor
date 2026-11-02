package com.logvex.addon.schematicdropper.core;

/** Applies and restores player rotation. Implemented over the Meteor rotation helper. */
public interface RotationPort {
    /**
     * Rotates toward {@code target} and returns the rotation the port is now aiming with.
     *
     * <p>Meteor's rotation helper restores the client's own yaw/pitch right after the movement
     * packet is sent, so the player's {@code getYaw()} cannot be used to detect convergence.
     * The port therefore keeps its own interpolated state and returns it here.
     *
     * @param instant when true the rotation snaps immediately, otherwise it steps by {@code speed}
     *                degrees per call
     */
    Rotation apply(Rotation target, boolean instant, float speed);

    /** Restores a rotation captured before the drop sequence started. */
    void restore(Rotation rotation);

    /** Drops any internal interpolation state, e.g. when a new run starts. */
    void reset();
}
