package com.logvex.addon.framework;

import meteordevelopment.orbit.IEventBus;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Shared client access points for the addon.
 *
 * <p>The addon is a Meteor Client addon: it publishes and listens on Meteor's own event bus so a
 * module can react to the same events as a built-in Meteor module, and Meteor's addon manager loads
 * it. This class is the one place feature code reaches the event bus, the game directory and the
 * client instance, so the rest of the codebase never has to know how the client is obtained.
 */
public final class LogClientContext {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String MOD_ID = "logclient";
    public static final String NAME = "Log Client";
    public static final String VERSION = "17.2.0";

    /** The addon-wide event bus: modules, HUD and the recorder all publish and listen here. */
    /**
     * The addon-wide event bus.
     *
     * <p>This is Meteor's bus, not a private one: subscribing here means the addon receives the same
     * {@code TickEvent}, {@code ReceiveMessageEvent} and packet events Meteor dispatches, and the
     * Meteor GUI drives the addon's modules exactly like its own.
     */
    public static final IEventBus EVENT_BUS = MeteorClient.EVENT_BUS;

    /** The Minecraft instance, for code that would otherwise call the singleton directly. */
    public static final MinecraftClient mc = MinecraftClient.getInstance();

    private LogClientContext() {
    }

    /** The Minecraft run directory, e.g. {@code .minecraft}. */
    public static File FOLDER() {
        try {
            return FabricLoader.getInstance().getGameDir().toFile();
        } catch (Throwable t) {
            // Fabric is not ready yet (very early bootstrap, or a plain unit test). Falling back to
            // ./run keeps path resolution from throwing; at real runtime the loader is always ready.
            return new File("run");
        }
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
