package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.EntityRef;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

import java.util.List;

/**
 * Hunts a named mob type for its drops.
 *
 * <p>Unlike {@link CombatExecutor}, which fights whatever is hostile, this targets one species and
 * measures success by the drop arriving in the inventory. That is the honest signal: the client is
 * not told when a mob dies, so "the mob is gone" can also mean it walked away or despawned, while a
 * new item in the bag cannot be faked.
 *
 * <p>The executor refuses to chase a mob it cannot see, never swings from out of reach, and gives up
 * on a target that stops existing rather than swinging at a stale id. It withdraws at the configured
 * health threshold like the combat executor does, because a hunt is still a fight.
 */
public final class HuntingExecutor implements TaskExecutor {
    /** Search radius for the prey, in blocks. */
    private static final int SEARCH_RADIUS = 24;
    /** A target inside this distance is in melee range. */
    private static final double MELEE_RANGE = 2.5;
    /** Minimum gap between swings. */
    private static final long SWING_MS = 600;
    /** Ticks to keep chasing one entity before giving up on it. */
    private static final int CHASE_TICKS = 200;

    @Override
    public TaskType type() {
        return TaskType.HUNTING;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        if (task.required() <= 0) task.required(1);
        task.setParameter("baseline", Integer.toString(dropCount(task, context)));
        task.setParameter("lastSwing", "0");
        task.setParameter("chase", "0");
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.world().canRun() || context.player() == null) return TaskOutcome.RETRY;

        if (context.player().health() <= context.config().healthThreshold()) {
            task.fail("Health fell too low to keep hunting.");
            return TaskOutcome.FAILED;
        }

        int baseline = parseInt(task.parameter("baseline", "0"));
        int gained = dropCount(task, context) - baseline;
        if (gained >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }
        task.setProgress(Math.max(0, gained));

        String prey = task.itemId();
        if (prey.isEmpty()) {
            task.fail("No mob type was named to hunt.");
            return TaskOutcome.FAILED;
        }

        EntityRef target = pickTarget(task, context, prey);
        if (target == null) {
            // Nothing of that species in range. Not a failure: it may wander back, and the caller
            // may have asked for several drops from a single spawn area.
            task.setParameter("chase", "0");
            task.setParameter("target", "");
            return TaskOutcome.RETRY;
        }

        task.setParameter("target", target.typeId());
        task.setParameter("targetDistance", String.format(java.util.Locale.ROOT, "%.1f", target.distance()));

        if (target.distance() > MELEE_RANGE) {
            int chase = parseInt(task.parameter("chase", "0")) + 1;
            if (chase > CHASE_TICKS) {
                // Abandon this individual rather than following it forever.
                task.setParameter("chase", "0");
                task.setParameter("avoid", Integer.toString(target.id()));
                return TaskOutcome.RETRY;
            }
            task.setParameter("chase", Integer.toString(chase));
            context.nav().start(target.pos());
            return TaskOutcome.CONTINUE;
        }

        task.setParameter("chase", "0");
        long now = context.clock().now();
        long lastSwing = parseLong(task.parameter("lastSwing", "0"));
        if (now - lastSwing < SWING_MS) return TaskOutcome.CONTINUE;

        if (context.actions().attack(target.id())) {
            task.setParameter("lastSwing", Long.toString(now));
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        String target = task.parameter("target", "");
        if (target.isEmpty()) return "Looking for " + shortName(task.itemId());
        return "Hunting " + shortName(target);
    }

    /**
     * The nearest live entity of {@code prey}, skipping any individual the executor gave up on.
     *
     * <p>The avoid id is cleared once that entity is gone from the scan, so a fresh mob of the same
     * species is not accidentally ignored.
     */
    private static EntityRef pickTarget(BotTask task, TaskContext context, String prey) {
        List<EntityRef> nearby = context.world().nearbyEntities(SEARCH_RADIUS);
        int avoid = parseInt(task.parameter("avoid", "0"));

        EntityRef best = null;
        boolean avoidSeen = false;
        for (EntityRef ref : nearby) {
            if (ref.id() == avoid) {
                avoidSeen = true;
                continue;
            }
            if (!prey.equals(ref.typeId())) continue;
            if (best == null || ref.distance() < best.distance()) best = ref;
        }
        if (!avoidSeen) task.setParameter("avoid", "0");
        return best;
    }

    /** How many of the hunted drop the player is carrying, so progress is measured, not assumed. */
    private static int dropCount(BotTask task, TaskContext context) {
        String drop = task.parameter("drop", "");
        if (!drop.isEmpty()) return context.inventory().snapshot().countOf(drop);
        return context.inventory().snapshot().totals().values().stream()
            .mapToInt(Integer::intValue).sum();
    }

    private static String shortName(String typeId) {
        if (typeId == null || typeId.isEmpty()) return "prey";
        int colon = typeId.indexOf(':');
        return colon >= 0 ? typeId.substring(colon + 1) : typeId;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
