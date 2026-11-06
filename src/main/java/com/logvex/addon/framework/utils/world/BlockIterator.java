package com.logvex.addon.framework.utils.world;

import com.logvex.addon.LogAddon;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Walks loaded chunks around the player and hands each block to registered callbacks.
 *
 * <p>Callbacks are queued by {@link #register} and drained on the next client tick by
 * {@link #tick}. The scan is bounded to loaded chunks inside the requested radius, so it never
 * forces chunk loads, and each callback is responsible for stopping early once it has what it needs.
 */
public final class BlockIterator {
    private static final List<Pending> PENDING = new ArrayList<>();

    private BlockIterator() {
    }

    private record Pending(int hRadius, int vRadius, BiConsumer<BlockPos, BlockState> callback) {
    }

    public static void register(int horizontalRadius, int verticalRadius, BiConsumer<BlockPos, BlockState> function) {
        PENDING.add(new Pending(horizontalRadius, verticalRadius, function));
    }

    /** Runs and clears every queued scan. Call once per client tick. */
    public static void tick() {
        if (PENDING.isEmpty()) return;

        List<Pending> batch = new ArrayList<>(PENDING);
        PENDING.clear();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;

        for (Pending pending : batch) {
            try {
                run(mc, pending);
            } catch (RuntimeException e) {
                LogAddon.LOG.error("Block scan failed", e);
            }
        }
    }

    private static void run(MinecraftClient mc, Pending pending) {
        BlockPos center = mc.player.getBlockPos();
        int h = Math.max(0, pending.hRadius());
        int v = Math.max(0, pending.vRadius());

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = -v; y <= v; y++) {
            for (int x = -h; x <= h; x++) {
                for (int z = -h; z <= h; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                    BlockState state = mc.world.getBlockState(pos);
                    pending.callback().accept(pos.toImmutable(), state);
                }
            }
        }
    }
}
