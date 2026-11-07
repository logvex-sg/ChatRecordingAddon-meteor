package com.logvex.addon.framework;

import com.logvex.addon.framework.orbit.EventBus;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Shared client access points for the addon.
 *
 * <p>The addon is standalone: it does not require Meteor Client or any other mod. This class is the
 * one place feature code reaches the {@link EventBus}, the game directory and the client instance,
 * so the rest of the codebase never has to know how the client is obtained.
 */
public final class LogClientContext {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String MOD_ID = "logclient";
    public static final String NAME = "Log Client";
    public static final String VERSION = "13.0.0";

    /** The addon-wide event bus: modules, HUD and the recorder all publish and listen here. */
    public static final EventBus EVENT_BUS = new EventBus();

    /** The Minecraft instance, for code that would otherwise call the singleton directly. */
    public static final MinecraftClient mc = MinecraftClient.getInstance();

    private LogClientContext() {
    }

    /** The Minecraft run directory, e.g. {@code .minecraft}. */
    public static File FOLDER() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    /** The addon's own data folder, {@code <gamedir>/logclient}. */
    public static File dataFolder() {
        return new File(FOLDER(), "logclient");
    }

    /**
     * Moves the pre-rebrand data folder ({@code <gamedir>/logaddon}) to its new name
     * ({@code <gamedir>/logclient}) once, before any service creates the new folder.
     *
     * <p>Without this, an upgrade from an older build would silently start from an empty config and
     * lose the recorded logs, identities and bot memory. The move is skipped when the new folder
     * already exists (so a fresh install or a second run is a no-op) and a failure is only logged:
     * an unmigrated install still runs, it just starts with empty data.
     */
    public static void migrateLegacyDataFolder() {
        File base = FOLDER();
        File legacy = new File(base, "logaddon");
        File current = new File(base, "logclient");
        if (!legacy.isDirectory() || current.exists()) return;
        try {
            Files.createDirectories(base.toPath());
            Files.move(legacy.toPath(), current.toPath());
            LOG.info("Migrated the data folder {} to {}", legacy, current);
        } catch (IOException e) {
            LOG.warn("Could not migrate the old data folder {}; starting with a fresh one", legacy, e);
        }
    }
}
