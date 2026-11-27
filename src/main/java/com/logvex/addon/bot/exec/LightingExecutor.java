package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.bot.core.WorldPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lights up dark ground so mobs stop spawning there.
 *
 * <p>The bot looks for replaceable blocks whose light level is at or below the spawn threshold, walks
 * to the nearest one it can reach, and places a torch. A spot only counts as done when the light
 * level there has actually risen, so a torch the server refused cannot be reported as a fix.
 *
 * <p>This is the one place {@link WorldPort#lightAt} is used. The scan is bounded by the configured
 * radius and capped at {@link #MAX_CANDIDATES}, because asking for the light level of every block in
 * a wide area would cost more than the task is worth.
 */
public final class LightingExecutor implements TaskExecutor {
    /** Vanilla stops hostile spawns at light level 0 in most cases; 7 is the safe target. */
    private static final int DARK_THRESHOLD = 7;
    /** How close the bot must be to place a torch. */
    private static final double REACH = 3.5;
    /** Upper bound on light queries per scan, so a dark cave cannot stall a tick. */
    private static final int MAX_CANDIDATES = 64;
    /** Vertical search band around the player, in blocks. */
    private static final int VERTICAL = 4;

    @Override
    public TaskType type() {
        return TaskType.LIGHTING;
    }

    @Override
    public void begin(BotTask task, TaskContext context) {
        task.setParameter("placed", "0");
        task.setParameter("target", "");
        // A lighting objective with no count means "light what you can see", not "place zero".
        if (task.required() <= 0) task.required(1);
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        WorldPort world = context.world();
        if (!world.canRun()) return TaskOutcome.RETRY;

        int placed = parseInt(task.parameter("placed", "0"));
        if (placed >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }

        String torch = torchId(context);
        if (torch.isEmpty()) {
            // Out of light sources: report the partial result rather than pretending to finish.
            task.fail("No light source left in the inventory after placing " + placed + ".");
            return placed > 0 ? TaskOutcome.DONE : TaskOutcome.FAILED;
        }

        BlockPos3 target = currentTarget(task, world);
        if (target == null) {
            target = nextDarkSpot(context);
            if (target == null) {
                // Nothing dark left in range: the area is as lit as this radius allows.
                return placed > 0 ? TaskOutcome.DONE : TaskOutcome.RETRY;
            }
            task.setParameter("target", encode(target));
        }

        if (!context.nav().arrived(target, REACH)) {
            if (!context.nav().running() && !context.nav().start(target)) {
                task.setParameter("target", "");
                return TaskOutcome.RETRY;
            }
            context.nav().tick(context.clock().now());
            return TaskOutcome.CONTINUE;
        }

        int before = world.lightAt(target);
        if (!context.actions().place(target, torch)) return TaskOutcome.RETRY;

        // The placement is only real if the light moved. A rejected place leaves the level alone.
        int after = world.lightAt(target);
        if (after > before) {
            placed++;
            task.setParameter("placed", Integer.toString(placed));
            task.setProgress(Math.min(task.required(), placed));
        }
        task.setParameter("target", "");
        // Report the finish on the same step the count is met, rather than costing an extra tick.
        return placed >= task.required() ? TaskOutcome.DONE : TaskOutcome.CONTINUE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        context.nav().stop();
    }

    @Override
    public String status(BotTask task) {
        return "Placing lights (" + task.parameter("placed", "0") + "/" + task.required() + ")";
    }

    /**
     * The darkest reachable spot within range, or {@code null} when everything is already lit.
     *
     * <p>Candidates are gathered from the block types a torch can sit on, then filtered by light
     * level. Darkest first means the bot fixes the worst spots rather than whatever is nearest,
     * which is what actually stops spawns.
     */
    private static BlockPos3 nextDarkSpot(TaskContext context) {
        WorldPort world = context.world();
        BlockPos3 origin = world.playerPos();
        if (origin == null) return null;

        List<BlockPos3> floors = world.findBlocks(List.of(
            "minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone",
            "minecraft:dirt", "minecraft:grass_block", "minecraft:netherrack",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
            "minecraft:tuff", "minecraft:gravel", "minecraft:sand", "minecraft:sandstone",
            "minecraft:blackstone", "minecraft:basalt"), context.config().effectiveRadius());
        if (floors.isEmpty()) return null;

        List<BlockPos3> candidates = new ArrayList<>();
        for (BlockPos3 floor : floors) {
            if (Math.abs(floor.y() - origin.y()) > VERTICAL) continue;
            BlockPos3 above = new BlockPos3(floor.x(), floor.y() + 1, floor.z());
            // A torch needs an empty block above solid ground, and the spot must be dark.
            if (!world.isReplaceable(above)) continue;
            int light = world.lightAt(above);
            if (light < 0 || light > DARK_THRESHOLD) continue;
            candidates.add(above);
            if (candidates.size() >= MAX_CANDIDATES) break;
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator
            .comparingInt((BlockPos3 pos) -> world.lightAt(pos))
            .thenComparingDouble(pos -> pos.distance(origin)));
        return candidates.get(0);
    }

    /** The light source to place: a torch if held, otherwise any held light-emitting item. */
    private static String torchId(TaskContext context) {
        for (String id : LIGHT_ITEMS) {
            if (context.inventory().snapshot().countOf(id) > 0) return id;
        }
        return "";
    }

    private static final List<String> LIGHT_ITEMS = List.of(
        "minecraft:torch",
        "minecraft:soul_torch",
        "minecraft:lantern",
        "minecraft:soul_lantern",
        "minecraft:glowstone",
        "minecraft:sea_lantern",
        "minecraft:shroomlight",
        "minecraft:campfire");

    private static String encode(BlockPos3 pos) {
        return pos.x() + "," + pos.y() + "," + pos.z();
    }

    private static BlockPos3 decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos3(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BlockPos3 currentTarget(BotTask task, WorldPort world) {
        BlockPos3 pos = decode(task.parameter("target", ""));
        if (pos == null) return null;
        // A target that got filled while walking to it is no longer a spot for a torch.
        return world.isReplaceable(pos) ? pos : null;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
