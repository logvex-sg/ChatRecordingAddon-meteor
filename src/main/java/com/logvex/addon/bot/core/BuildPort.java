package com.logvex.addon.bot.core;

/**
 * The bridge between the bot and the schematic build subsystem. The Minecraft layer adapts the
 * existing {@code SchematicBuildController} so the bot can schedule a build without duplicating the
 * placement, verification or checkpoint logic that already exists.
 */
public interface BuildPort {
    /** True when a schematic is loaded and ready to build. */
    boolean hasSchematic();

    /** The loaded schematic's name, or empty. */
    String schematicName();

    /** Starts (or resumes) a build. Returns false when nothing can be built. */
    boolean startBuild();

    /** Stops the current build. */
    void stopBuild();

    /** True while a build is running. */
    boolean running();

    /** Build progress in {@code [0, 1]}. */
    double progress();

    /** A short status line for the dashboard. */
    String status();

    /** The last build error, or empty. */
    String lastError();
}
