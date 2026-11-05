package com.logvex.addon.bot.core;

/** Live player state: position, health, hunger, game mode and the current screen. */
public interface PlayerPort {
    /** True when the player is in Creative mode. */
    boolean isCreative();

    /** True when the player is in Survival mode. */
    boolean isSurvival();

    /** Health in half-hearts. */
    float health();

    /** Maximum health in half-hearts. */
    float maxHealth();

    /** Food level in half-shanks. */
    int foodLevel();

    /** True when a screen other than the game is open. */
    boolean screenOpen();

    /** True when the player is on the ground. */
    boolean onGround();

    /** The player's exact position as {@code [x, y, z]}. */
    double[] position();

    /** The player's current dimension id. */
    String dimension();

    /** True when the player is alive. */
    boolean alive();
}
