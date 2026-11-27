package com.logvex.addon.common;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place that reads the live client world for nearby monsters.
 *
 * <p>Every feature that needs the answer — the bot's safety stop and auto-eat, the HUD, the
 * dashboard snapshot and the automation modules — goes through this class, so there is a single
 * cache rather than one per feature. Without that, four callers in one tick each built their own
 * box, their own entity list and their own walk of it, all to compute the same boolean.
 *
 * <p>Reads are restricted to the client thread. The world's entity collection is not safe to walk
 * from an HTTP worker, and the companion server does run on its own threads, so a call from
 * anywhere else answers "no threat" instead of risking a concurrent-modification crash. That is the
 * conservative answer: the callers are all advisory, and the safety stop re-runs on the next tick
 * from the thread that is allowed to ask.
 */
public final class Hostiles {

    private static final HostileScan SCAN = new HostileScan();

    /** Scratch list for {@link #scan}; cleared per scan so a query allocates nothing but its box. */
    private static final List<HostileEntity> found = new ArrayList<>(16);

    private Hostiles() {
    }

    /**
     * The nearest hostile to {@code entity}'s eyes, scanning at most once per tick.
     *
     * @param entity the player whose surroundings to read
     * @param radius how far to look, in blocks
     */
    public static HostileScan.Hit nearest(Entity entity, double radius) {
        if (entity == null) return HostileScan.NONE;
        World world = entity.getWorld();
        if (world == null) return HostileScan.NONE;
        if (!net.minecraft.client.MinecraftClient.getInstance().isOnThread()) return HostileScan.NONE;
        return SCAN.nearest(world.getTime(), world,
            entity.getX(), entity.getY(), entity.getZ(), radius,
            (x, y, z, reach) -> scan(world, entity, x, y, z, reach));
    }

    /** True when a hostile is within {@code radius} of {@code entity}. */
    public static boolean anyWithin(Entity entity, double radius) {
        return nearest(entity, radius).distance() <= Math.max(1, radius);
    }

    /** Forgets the cached answer; called when the world changes. */
    public static void clear() {
        SCAN.clear();
    }

    /**
     * Walks the box and keeps the nearest distance.
     *
     * <p>The box is built from the query centre rather than the player's hitbox, so the answer is
     * centred where the caller asked. {@code TypeFilter.instanceOf} lets the world walk only the
     * entity sections that can hold the type, instead of every entity in range, and
     * {@code collectEntitiesByType} fills a list we own rather than allocating a fresh one per scan.
     *
     * <p>That list is reused across calls, which is safe because this class only reads on the
     * client thread and the predicate cannot re-enter it.
     */
    private static HostileScan.Hit scan(World world, Entity exclude, double x, double y, double z, double radius) {
        double half = Math.max(1, radius);
        Box box = new Box(x - half, y - half, z - half, x + half, y + half, z + half);
        found.clear();
        world.collectEntitiesByType(TypeFilter.instanceOf(HostileEntity.class), box, Entity::isAlive, found);

        double best = Double.POSITIVE_INFINITY;
        int count = 0;
        for (int i = 0; i < found.size(); i++) {
            HostileEntity hostile = found.get(i);
            if (hostile == exclude) continue;
            count++;
            double squared = hostile.squaredDistanceTo(x, y, z);
            if (squared < best) best = squared;
        }
        return count == 0 ? HostileScan.NONE
            : new HostileScan.Hit(Math.sqrt(best), count);
    }
}
