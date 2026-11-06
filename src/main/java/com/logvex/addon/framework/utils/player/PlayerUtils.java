package com.logvex.addon.framework.utils.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.world.GameMode;

/** Client-side player state that does not need a packet round trip. */
public final class PlayerUtils {
    private PlayerUtils() {
    }

    /**
     * The player's game mode as the server last reported it, or {@code null} when it is unknown
     * (not in a world, or the player list entry has not arrived yet).
     */
    public static GameMode getGameMode() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return null;
        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? null : entry.getGameMode();
    }
}
