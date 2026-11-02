package com.logvex.addon.schematicdropper.core;

import java.util.List;
import java.util.Map;

/**
 * Everything the engine needs to read from the Minecraft world. Implemented by an adapter over the
 * live client; the engine itself never touches Minecraft types.
 */
public interface EnvPort {
    /** True when a player and a world exist and the client is in a state where dropping is safe. */
    boolean canRun();

    /** True when the current screen handler can accept slot clicks. */
    boolean canInteract();

    /** Total number of {@code itemId} currently held, across the whole player inventory. */
    int countOf(String itemId);

    /** Stacks of {@code itemId} in ascending slot order. Empty when the material is not held. */
    List<StackEntry> stacksOf(String itemId);

    /** Total count of every item currently held, keyed by registry id. */
    Map<String, Integer> inventorySnapshot();

    /** Rotation the player currently has. */
    Rotation currentRotation();

    /**
     * Rotation that points at the intended drop direction, or {@code null} when no valid target
     * exists (no world, no target entity, invalid coordinates).
     */
    Rotation desiredRotation();
}
