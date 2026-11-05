package com.logvex.addon.bot.core;

/**
 * The side-effecting operations the task executors may perform. Grouping them keeps every executor
 * testable with a fake and keeps the Minecraft layer in one place.
 *
 * <p>Every method here issues a real vanilla interaction and reports whether the interaction was
 * accepted. It never reports that the world or the inventory changed: the caller verifies the
 * effect itself, because a click the server rejects must not be mistaken for success.
 */
public interface ActionPorts {
    /** Mines {@code pos} and returns true once the block has actually been removed. */
    boolean mine(BlockPos3 pos);

    /** Places {@code itemId} at {@code pos}. Returns true once the world shows the placed block. */
    boolean place(BlockPos3 pos, String itemId);

    /** Interacts with the block at {@code pos} (open a container, use a crafting table, etc.). */
    boolean interact(BlockPos3 pos);

    /** Uses the currently held item (e.g. casts a fishing rod). */
    boolean useItem();

    /** True when a crafting screen (inventory grid or crafting table) is open. */
    boolean craftingReady();

    /** Performs one craft of {@code recipeId}. Returns false when it cannot start. */
    boolean craft(String recipeId, int times);

    /** True when a furnace-like screen is open. */
    boolean furnaceReady();

    /** Smelts one batch in the open furnace. Returns false when it cannot start. */
    boolean smelt(String inputId, String fuelId, int count);

    /** True when the bot is currently allowed to act (no blocking screen, world present). */
    boolean canAct();

    /** Cancels any in-flight operation. */
    void cancel();
}
