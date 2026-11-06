package com.logvex.addon.framework;

import com.logvex.addon.framework.orbit.EventBus;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.File;

/**
 * Shared client access points, kept under the historical name so feature code can reach the event
 * bus, the game folder and the client instance from one place.
 */
public final class MeteorClient {
    public static final String MOD_ID = "logvex-addon";
    public static final EventBus EVENT_BUS = new EventBus();

    /** The Minecraft instance, for code that would otherwise call the singleton directly. */
    public static final MinecraftClient mc = MinecraftClient.getInstance();

    private MeteorClient() {
    }

    /** The Minecraft run directory, e.g. {@code .minecraft}. */
    public static File FOLDER() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }
}
