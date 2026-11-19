package com.logvex.addon.stats;

import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Reads a {@link ServerStats} snapshot from the running client.
 *
 * <p>The tick rate comes from Meteor's own {@link TickRate} tracker, which measures the interval
 * between server keep-alives; everything else is read straight off the client and the player. A
 * figure the client cannot observe is reported as absent (-1 for latency, 0 for the tick rate) rather
 * than invented, and the whole snapshot is {@link ServerStats#OFFLINE} outside a world.
 *
 * <p>Everything here is derived from what the server has already sent the client, so it behaves the
 * same in single-player, on a vanilla server and on a proxy: the client never asks the server for a
 * figure, it only reads what it was told. That also means a figure the server withholds — the player
 * maximum, or the biome of an unloaded chunk — is absent rather than guessed.
 *
 * <p>All of this is client-thread work: it touches the world, the player and the renderer.
 */
public final class McServerStats {
    private McServerStats() {
    }

    /**
     * The current snapshot.
     *
     * @param sessionStart when the current session began, for the uptime figure
     * @return {@link ServerStats#OFFLINE} when there is no world or player
     */
    public static ServerStats snapshot(MinecraftClient mc, long now, long sessionStart) {
        return snapshot(mc, now, sessionStart, 0.0);
    }

    /**
     * The current snapshot with a measured walking speed.
     *
     * @param blocksPerSecond horizontal speed, already measured by the caller
     */
    public static ServerStats snapshot(MinecraftClient mc, long now, long sessionStart,
                                       double blocksPerSecond) {
        if (mc == null || mc.player == null || mc.world == null) return ServerStats.OFFLINE;

        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        // The client only knows the players the server has sent it, which is the full list on most
        // servers but a subset on ones that hide players; the maximum is never transmitted.
        int players = mc.getNetworkHandler() == null ? 0 : mc.getNetworkHandler().getPlayerList().size();
        RegistryKey<World> key = mc.world.getRegistryKey();

        return new ServerStats(
            true,
            serverName(mc),
            TickRate.INSTANCE.getTickRate(),
            latency(mc),
            players,
            0,
            Math.max(0L, now - sessionStart),
            key.getValue().toString(),
            key.getValue().getPath(),
            biomeName(mc),
            mc.world.getTimeOfDay(),
            mc.player.getHealth(),
            mc.player.getMaxHealth(),
            mc.player.getHungerManager().getFoodLevel(),
            mc.player.getX(),
            mc.player.getY(),
            mc.player.getZ(),
            ServerStats.facingName(mc.player.getYaw()),
            Math.max(0.0, blocksPerSecond),
            mc.getCurrentFps(),
            usedMb,
            maxMb,
            onlineNames(mc)
        );
    }

    /**
     * The names in the tab list, sorted, so the same server always reads the same way.
     *
     * <p>Read from the player list rather than tracked from chat, so a player who has not spoken is
     * still counted. Servers that hide players from the tab list will show fewer names than are
     * really online; there is no client-side way to see the rest.
     */
    private static java.util.List<String> onlineNames(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null) return java.util.List.of();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() == null) continue;
            String name = entry.getProfile().getName();
            if (name != null && !name.isBlank()) names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return java.util.List.copyOf(names);
    }

    private static int latency(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null || mc.player == null) return -1;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? -1 : entry.getLatency();
    }

    private static String serverName(MinecraftClient mc) {
        var entry = mc.getCurrentServerEntry();
        if (entry == null) return "single-player";
        return entry.name == null || entry.name.isBlank() ? entry.address : entry.name;
    }

    /**
     * The biome at the player's feet, or empty when the chunk is not loaded.
     *
     * <p>Read from the client's own chunk data rather than a server query, so it costs nothing and
     * works on every server; an unloaded chunk yields nothing rather than a misleading default.
     */
    private static String biomeName(MinecraftClient mc) {
        BlockPos pos = mc.player.getBlockPos();
        if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return "";
        var entry = mc.world.getBiome(pos);
        return entry.getKey().map(RegistryKey::getValue).map(id -> id.getPath()).orElse("");
    }
}
