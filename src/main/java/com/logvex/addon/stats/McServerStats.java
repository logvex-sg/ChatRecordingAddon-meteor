package com.logvex.addon.stats;

import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

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
 * <p>All of this is client-thread work: it touches the world, the player and the renderer. It is
 * cheap (a handful of field reads) and is called at most once per reply or per HUD frame, never on a
 * timer of its own.
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
        TpsWindow.sample(TickRate.INSTANCE.getTickRate(), now);
        RegistryKey<World> key = mc.world.getRegistryKey();
        ServerInfo entry = mc.getCurrentServerEntry();

        return new ServerStats(
            true,
            serverName(mc),
            entry == null || entry.address == null ? "" : entry.address,
            entry == null,
            TickRate.INSTANCE.getTickRate(),
            latency(mc),
            players,
            // Nothing in the client protocol carries this. It stays absent, and the renderers print
            // n/a, rather than being filled in from a guess.
            0,
            Math.max(0L, now - sessionStart),
            key.getValue().toString(),
            key.getValue().getPath(),
            biomeName(mc),
            mc.world.getTimeOfDay(),
            mc.world.getTimeOfDay() / 24000L,
            ServerStats.weatherName(mc.world.isRaining(), mc.world.isThundering()),
            mc.getCurrentFps(),
            usedMb,
            maxMb,
            onlineNames(mc),
            playerView(mc, blocksPerSecond),
            TpsWindow.average(TpsWindow.ONE_MINUTE, now),
            TpsWindow.average(5 * TpsWindow.ONE_MINUTE, now),
            TpsWindow.average(15 * TpsWindow.ONE_MINUTE, now),
            freeMemoryMb(),
            serverVersion(mc),
            serverBrand(mc),
            -1L,
            sessionStart,
            difficultyName(mc),
            net.minecraft.SharedConstants.getGameVersion().getName(),
            fabricVersion(),
            modCount()
        );
    }

    /** The local player's own figures, all read from {@code mc.player}. */
    private static PlayerView playerView(MinecraftClient mc, double blocksPerSecond) {
        var player = mc.player;
        if (player == null) return PlayerView.EMPTY;
        return new PlayerView(
            player.getHealth(),
            player.getMaxHealth(),
            player.getAbsorptionAmount(),
            player.getHungerManager().getFoodLevel(),
            gameMode(mc) == null ? -1 : gameMode(mc).getId(),
            gameModeName(mc),
            player.getX(),
            player.getY(),
            player.getZ(),
            ServerStats.facingName(player.getYaw()),
            Math.max(0.0, blocksPerSecond),
            itemName(player.getMainHandStack()),
            itemName(player.getOffHandStack()),
            armor(player),
            effects(player),
            target(mc),
            mc.crosshairTarget instanceof EntityHitResult,
            player.getHungerManager().getSaturationLevel(),
            player.experienceLevel,
            player.experienceProgress,
            player.getAir(),
            totemCount(player)
        );
    }

    /**
     * The game mode as a lower-case name, read from the client's interaction manager.
     *
     * <p>The client is told its own game mode, so this is a server figure the client can trust
     * rather than one it has to infer.
     */
    private static String gameModeName(MinecraftClient mc) {
        var mode = gameMode(mc);
        return mode == null ? "" : mode.getName();
    }

    private static net.minecraft.world.GameMode gameMode(MinecraftClient mc) {
        return mc.interactionManager == null ? null : mc.interactionManager.getCurrentGameMode();
    }

    /**
     * The block or entity under the crosshair.
     *
     * <p>Read from {@code crosshairTarget}, which the client already computes every frame for the
     * block outline; this costs nothing extra. A block carries its position so two blocks of the
     * same kind are distinguishable.
     */
    private static String target(MinecraftClient mc) {
        HitResult hit = mc.crosshairTarget;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return "";
        if (hit instanceof BlockHitResult block) {
            BlockPos pos = block.getBlockPos();
            var state = mc.world.getBlockState(pos);
            String name = state.getBlock().getName().getString();
            return name + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        }
        if (hit instanceof EntityHitResult entity && entity.getEntity() != null) {
            return entity.getEntity().getName().getString();
        }
        return "";
    }

    /** The four armour slots, labelled, with empty slots omitted. */
    private static List<String> armor(LivingEntity player) {
        List<String> out = new ArrayList<>(4);
        addArmor(out, "Head", player.getEquippedStack(EquipmentSlot.HEAD));
        addArmor(out, "Chest", player.getEquippedStack(EquipmentSlot.CHEST));
        addArmor(out, "Legs", player.getEquippedStack(EquipmentSlot.LEGS));
        addArmor(out, "Feet", player.getEquippedStack(EquipmentSlot.FEET));
        return List.copyOf(out);
    }

    private static void addArmor(List<String> out, String slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        out.add(slot + ": " + itemName(stack));
    }

    /** Active status effects as {@code name level (time)} entries. */
    private static List<String> effects(LivingEntity player) {
        List<String> out = new ArrayList<>();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            String name = effect.getEffectType().value().getName().getString();
            String time = effect.getDuration() < 0
                ? "infinite"
                : ServerStats.formatUptime(effect.getDuration() * 50L);
            int amplifier = effect.getAmplifier();
            out.add(name + (amplifier > 0 ? " " + roman(amplifier + 1) : "") + " (" + time + ")");
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    /** A small Roman numeral, as the vanilla effect HUD shows for an amplified effect. */
    private static String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(value);
        };
    }

    /** An item's display name, with its stack size when it is more than one. */
    private static String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        String name = stack.getName().getString();
        return stack.getCount() > 1 ? name + " x" + stack.getCount() : name;
    }

    /**
     * The names in the tab list, sorted, so the same server always reads the same way.
     *
     * <p>Read from the player list rather than tracked from chat, so a player who has not spoken is
     * still counted. Servers that hide players from the tab list will show fewer names than are
     * really online; there is no client-side way to see the rest.
     */
    private static List<String> onlineNames(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null) return List.of();
        List<String> names = new ArrayList<>();
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() == null) continue;
            String name = entry.getProfile().getName();
            if (name != null && !name.isBlank()) names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    private static int latency(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null || mc.player == null) return -1;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? -1 : entry.getLatency();
    }

    private static String serverName(MinecraftClient mc) {
        ServerInfo entry = mc.getCurrentServerEntry();
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

    // ------------------------------------------------------------------ rolling tick rate

    /**
     * Rolling one, five and fifteen minute tick-rate averages.
     *
     * <p>Meteor's {@link TickRate} reports one instantaneous figure. An average over a window is what
     * tells a player whether the server has been steady or just recovered, so the samples are kept
     * here and averaged on demand. A window with no samples is reported as absent (-1) rather than
     * as the current rate, so a fresh join does not claim a five-minute average it never measured.
     */
    private static final class TpsWindow {
        private static final long ONE_MINUTE = 60_000L;
        private static final long SAMPLE_GAP_MS = 1_000L;
        private static final int CAPACITY = 16 * 60;

        private static final long[] TIMES = new long[CAPACITY];
        private static final float[] VALUES = new float[CAPACITY];
        private static int head;
        private static int size;
        private static long lastSampleAt;

        private TpsWindow() {
        }

        static synchronized void sample(float tps, long now) {
            if (tps <= 0f) return;
            if (now - lastSampleAt < SAMPLE_GAP_MS) {
                // Keep the newest value in place so a rapid burst of replies sees a fresh figure.
                if (size > 0) VALUES[(head - 1 + CAPACITY) % CAPACITY] = tps;
                return;
            }
            lastSampleAt = now;
            TIMES[head] = now;
            VALUES[head] = tps;
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) size++;
        }

        /** The mean over {@code windowMs}, or -1 when nothing was sampled in that window. */
        static synchronized float average(long windowMs, long now) {
            float sum = 0f;
            int count = 0;
            for (int i = 0; i < size; i++) {
                int index = (head - 1 - i + CAPACITY * 2) % CAPACITY;
                if (now - TIMES[index] > windowMs) break;
                sum += VALUES[index];
                count++;
            }
            return count == 0 ? -1f : sum / count;
        }
    }

    /** The Fabric Loader version, or empty when it cannot be read. */
    private static String fabricVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** How many mods are loaded, or 0 when the loader cannot say. */
    private static int modCount() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().size();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** The free heap in megabytes, read from the running JVM. */
    private static long freeMemoryMb() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.freeMemory() / (1024L * 1024L));
    }

    /**
     * The server software brand, when the client was told it.
     *
     * <p>Vanilla and most proxies send their brand in a plugin message, which the client stores on
     * the network handler. An offline-mode or plugin-stripped server sends nothing, so this is
     * absent rather than guessed.
     */
    private static String serverBrand(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null) return "";
        String brand = mc.getNetworkHandler().getBrand();
        return brand == null ? "" : brand.strip();
    }

    /** The server's game version as reported in the server list entry, or empty when unknown. */
    private static String serverVersion(MinecraftClient mc) {
        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry == null || entry.version == null) return "";
        String version = entry.version.getString();
        return version == null ? "" : version.strip();
    }

    /** The world difficulty, which single-player knows and a server sends on request. */
    private static String difficultyName(MinecraftClient mc) {
        if (mc.world == null) return "";
        try {
            return mc.world.getDifficulty().getName();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** The number of totems of undying carried anywhere in the player's inventory. */
    private static int totemCount(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        // The offhand is part of the inventory on modern versions, but count it defensively so a
        // totem held there is never missed.
        if (player.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)
            && count == 0) {
            count = player.getOffHandStack().getCount();
        }
        return count;
    }
}
