package com.logvex.addon.framework;

import com.logvex.addon.framework.orbit.EventBus;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.File;

/**
 * Shared client access points for the addon.
 *
 * <p>The addon is standalone: it does not require Meteor Client or any other mod. This class is the
 * one place feature code reaches the {@link EventBus}, the game directory and the client instance,
 * so the rest of the codebase never has to know how the client is obtained.
 */
public final class Addon {
    public static final String MOD_ID = "logvex-addon";
    public static final String NAME = "Log Addon";
    public static final String VERSION = "5.0.0";

    /** The addon-wide event bus: modules, HUD and the recorder all publish and listen here. */
    public static final EventBus EVENT_BUS = new EventBus();

    /** The Minecraft instance, for code that would otherwise call the singleton directly. */
    public static final MinecraftClient mc = MinecraftClient.getInstance();

    private Addon() {
    }

    /** The Minecraft run directory, e.g. {@code .minecraft}. */
    public static File FOLDER() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    /** The addon's own data folder, {@code <gamedir>/logaddon}. */
    public static File dataFolder() {
        return new File(FOLDER(), "logaddon");
    }
}
