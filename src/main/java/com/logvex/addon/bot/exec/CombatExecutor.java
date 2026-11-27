package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.EntityRef;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

import java.util.List;

/**
 * Fights hostile mobs until the area is clear or the requested number is killed.
 *
 * <p>The executor is deliberately defensive. It re-scans every step rather than trusting a stale
 * target, it never swings at something out of reach, and it withdraws when health falls below the
 * configured threshold instead of trading blows it cannot win. Progress is counted from hostiles
 * that disappear from the scan, which is the only evidence the client has that a mob died.
 */
public final class CombatExecutor implements TaskExecutor {
    /** How far the bot will look for a target, in blocks. */
    private static final int ENGAGE_RADIUS = 12;
    /** A target this close is already in melee range; no navigation needed. */
    private static final double MELEE_RANGE = 2.5;
    /** Minimum gap between swings, so the executor does not spam attacks on every tick. */
    private static final long SWING_MS = 600;

    @Override
    public TaskType type() {
        return TaskType.COMBAT;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        // A combat objective with no count means "clear the area", not "kill zero".
        if (task.required() <= 0) task.required(1);
        task.setParameter("baseline", Integer.toString(hostileCount(context)));
        task.setParameter("killed", "0");
        task.setParameter("lastSwing", "0");
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (!context.world().canRun() || context.player() == null) return TaskOutcome.RETRY;

        // Losing the fight is a failure, not something to grind through: the safety controller
        // normally pauses first, and reaching here means the pause could not happen.
        if (context.player().health() <= context.config().healthThreshold()) {
            task.fail("Health fell too low to keep fighting.");
            return TaskOutcome.FAILED;
        }

        List<EntityRef> hostiles = hostiles(context);
        int killed = parseInt(task.parameter("killed", "0"));
        int baseline = parseInt(task.parameter("baseline", "0"));
        // Fewer hostiles than before means some died; the drop is the kill count. Guard against a
        // mob simply walking out of range by never letting the count go backwards.
        int nowCount = hostiles.size();
        int observed = Math.max(0, baseline - nowCount);
        if (observed > killed) {
            killed = observed;
            task.setParameter("killed", Integer.toString(killed));
            task.setProgress(Math.min(task.required(), killed));
        }
        if (killed >= task.required()) return TaskOutcome.DONE;

        if (hostiles.isEmpty()) {
            // Nothing to fight and nothing killed: the objective cannot be met here.
            if (killed == 0) {
                task.fail("No hostile mobs are within range.");
                return TaskOutcome.FAILED;
            }
            return TaskOutcome.DONE;
        }

        EntityRef target = hostiles.get(0);
        task.setParameter("target", target.typeId());
        task.setParameter("targetDistance", String.format(java.util.Locale.ROOT, "%.1f", target.distance()));

        if (target.distance() > MELEE_RANGE) {
            // Close the distance first. Navigation is fire-and-forget here; the next step re-checks
            // the distance, so a path that fails simply leaves the target far and we try again.
            context.nav().start(target.pos());
            return TaskOutcome.CONTINUE;
        }

        long now = context.clock().now();
        long lastSwing = parseLong(task.parameter("lastSwing", "0"));
        if (now - lastSwing < SWING_MS) return TaskOutcome.CONTINUE;

        if (context.actions().attack(target.id())) {
            task.setParameter("lastSwing", Long.toString(now));
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public String status(BotTask task) {
        String target = task.parameter("target", "");
        if (target.isEmpty()) return "Looking for hostiles";
        return "Fighting " + shortName(target);
    }

    private static List<EntityRef> hostiles(TaskContext context) {
        return context.world().nearbyEntities(ENGAGE_RADIUS).stream()
            .filter(EntityRef::hostile)
            .toList();
    }

    private static int hostileCount(TaskContext context) {
        return hostiles(context).size();
    }

    private static String shortName(String typeId) {
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
