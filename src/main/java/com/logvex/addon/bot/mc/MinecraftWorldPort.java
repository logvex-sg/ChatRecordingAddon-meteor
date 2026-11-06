package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.WorldPort;
import com.logvex.addon.framework.utils.world.BlockIterator;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.logvex.addon.framework.Addon.mc;

/**
 * {@link WorldPort} backed by the live client world.
 *
 * <p>Block searches use the addon's {@link BlockIterator}, which walks only loaded chunks around the
 * player on the client tick and stops at a hard cap, so a large radius cannot freeze the game. Every
 * scan is bounded by {@code maxResults}; the bot never asks for an unbounded world scan.
 */
public final class MinecraftWorldPort implements WorldPort {
    /** Hard cap on how many blocks one search may return. */
    private static final int MAX_RESULTS = 1024;

    private volatile int maxResults = 512;

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
        int cap = maxResults;
        int r = Math.max(1, radius);

        // BlockIterator requires the caller to be on the client tick; it invokes the callback for
        // every block in range, so the callback itself must be cheap and must stop early.
        AtomicInteger found = new AtomicInteger();
        BlockIterator.register(r, r, (pos, state) -> {
            if (found.get() >= cap) return;
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            if (wanted.contains(id)) {
                out.add(new BlockPos3(pos.getX(), pos.getY(), pos.getZ()));
                found.incrementAndGet();
            }
        });
        return out;
    }

    @Override
    public List<BlockPos3> findContainers(int radius) {
        List<BlockPos3> out = new ArrayList<>();
        if (mc.world == null || mc.player == null) return out;
        int cap = maxResults;
        int r = Math.max(1, radius);
        BlockIterator.register(r, r, (pos, state) -> {
            if (out.size() >= cap) return;
            BlockEntity entity = mc.world.getBlockEntity(pos);
            if (entity instanceof LootableContainerBlockEntity) {
                out.add(new BlockPos3(pos.getX(), pos.getY(), pos.getZ()));
            }
        });
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

    /** True when a hostile mob is within {@code radius} of the player. */
    public boolean hostileNearby(int radius) {
        if (mc.world == null || mc.player == null) return false;
        Box box = mc.player.getBoundingBox().expand(Math.max(1, radius));
        for (Entity entity : mc.world.getOtherEntities(mc.player, box)) {
            if (entity instanceof HostileEntity hostile && hostile.isAlive()) return true;
        }
        return false;
    }
}
