package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.EntityRef;
import com.logvex.addon.bot.core.WorldPort;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * {@link WorldPort} backed by the live client world.
 *
 * <p>Block searches walk the box around the player directly, skipping unloaded chunks and stopping
 * at a hard cap, so a large radius cannot freeze the game. Every scan is bounded by
 * {@code maxResults}; the bot never asks for an unbounded world scan.
 */
public final class MinecraftWorldPort implements WorldPort {
    /** Hard cap on how many blocks one search may return. */
    private static final int MAX_RESULTS = 1024;

    private volatile int maxResults = 512;

    /** Reused by {@link #findBlocks}, which visits thousands of positions per call. */
    private final BlockPos.Mutable blockPos = new BlockPos.Mutable();

    public void setMaxResults(int value) {
        this.maxResults = Math.max(1, Math.min(MAX_RESULTS, value));
    }

    @Override
    public boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null;
    }

    @Override
    public String dimension() {
        if (mc.world == null) return "";
        return mc.world.getRegistryKey().getValue().toString();
    }

    @Override
    public BlockPos3 playerPos() {
        if (mc.player == null) return null;
        return new BlockPos3(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
    }

    @Override
    public String blockAt(BlockPos3 pos) {
        if (mc.world == null || pos == null) return null;
        BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!mc.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) return null;
        BlockState state = mc.world.getBlockState(p);
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    @Override
    public boolean isLoaded(BlockPos3 pos) {
        if (mc.world == null || pos == null) return false;
        return mc.world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    @Override
    public List<BlockPos3> findBlocks(List<String> blockIds, int radius) {
        List<BlockPos3> out = new ArrayList<>();
        if (mc.world == null || mc.player == null || blockIds == null || blockIds.isEmpty()) return out;
        Set<String> wanted = new HashSet<>(blockIds);
        int cap = Math.max(1, maxResults);
        // A search must be a pure read: the executor asks for candidates and acts on the answer in
        // the same tick, so the answer cannot be deferred. This walks the box itself rather than
        // BlockIterator, which only queues a callback for the next TickEvent.Pre and therefore
        // always returned an empty list when called from the bot's own tick.
        int r = Math.max(1, radius);
        int px = mc.player.getBlockX();
        int py = mc.player.getBlockY();
        int pz = mc.player.getBlockZ();
        int minY = Math.max(mc.world.getBottomY(), py - r);
        int maxY = Math.min(mc.world.getTopY(), py + r);

        for (int x = px - r; x <= px + r && out.size() < cap; x++) {
            for (int z = pz - r; z <= pz + r && out.size() < cap; z++) {
                if (!mc.world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    String id = Registries.BLOCK.getId(
                        mc.world.getBlockState(blockPos.set(x, y, z)).getBlock()).toString();
                    if (!wanted.contains(id)) continue;
                    out.add(new BlockPos3(x, y, z));
                    if (out.size() >= cap) break;
                }
            }
        }
        // Nearest first, so the bot stops at the closest ore rather than walking past it to a
        // farther match that happened to come earlier in the scan order.
        out.sort(Comparator.comparingDouble(p -> distanceSq(p, px, py, pz)));
        return out;
    }

    private static double distanceSq(BlockPos3 pos, int x, int y, int z) {
        double dx = pos.x() - x;
        double dy = pos.y() - y;
        double dz = pos.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public List<BlockPos3> findContainers(int radius) {
        List<BlockPos3> out = new ArrayList<>();
        if (mc.world == null || mc.player == null) return out;
        int cap = Math.max(1, maxResults);
        // Same reason as findBlocks: the caller reads the list in this tick, so a deferred
        // BlockIterator callback would always answer with nothing.
        int r = Math.max(1, radius);
        int px = mc.player.getBlockX();
        int py = mc.player.getBlockY();
        int pz = mc.player.getBlockZ();
        int minY = Math.max(mc.world.getBottomY(), py - r);
        int maxY = Math.min(mc.world.getTopY(), py + r);

        for (int x = px - r; x <= px + r && out.size() < cap; x++) {
            for (int z = pz - r; z <= pz + r && out.size() < cap; z++) {
                if (!mc.world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = blockPos.set(x, y, z);
                    if (mc.world.getBlockEntity(pos) instanceof LootableContainerBlockEntity) {
                        out.add(new BlockPos3(x, y, z));
                        if (out.size() >= cap) break;
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> distanceSq(p, px, py, pz)));
        return out;
    }

    @Override
    public List<String> nearbyEntityTypes(int radius) {
        List<String> out = new ArrayList<>();
        if (mc.world == null || mc.player == null) return out;
        double r = Math.max(1, radius);
        Box box = mc.player.getBoundingBox().expand(r);
        for (Entity entity : mc.world.getOtherEntities(mc.player, box)) {
            if (entity instanceof LivingEntity living && living.isAlive()) {
                out.add(Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            }
        }
        return out;
    }

    @Override
    public boolean isReplaceable(BlockPos3 pos) {
        if (mc.world == null || pos == null) return false;
        BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!mc.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) return false;
        return mc.world.getBlockState(p).isReplaceable();
    }

    @Override
    public int lightAt(BlockPos3 pos) {
        if (mc.world == null || pos == null) return -1;
        BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!mc.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) return -1;
        return mc.world.getLightLevel(p);
    }

    @Override
    public List<EntityRef> nearbyEntities(int radius) {
        List<EntityRef> out = new ArrayList<>();
        if (mc.world == null || mc.player == null) return out;
        double r = Math.max(1, radius);
        Box box = mc.player.getBoundingBox().expand(r);
        Vec3d eye = mc.player.getPos();
        for (Entity entity : mc.world.getOtherEntities(mc.player, box)) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            Vec3d at = entity.getPos();
            double distance = Math.sqrt(eye.squaredDistanceTo(at));
            if (distance > r) continue;
            out.add(new EntityRef(
                entity.getId(),
                Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                new BlockPos3(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()),
                distance,
                entity instanceof HostileEntity));
        }
        out.sort(Comparator.comparingDouble(EntityRef::distance));
        return out;
    }

    @Override
    public boolean hostileNearby(int radius) {
        if (mc.world == null || mc.player == null) return false;
        // Shared, per-tick cached scan: the safety stop and auto-eat both ask in the same tick.
        return com.logvex.addon.common.Hostiles.anyWithin(mc.player, Math.max(1, radius));
    }
}
