package com.logvex.addon.command;

import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.framework.commands.Command;
import com.logvex.addon.profiles.BotProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;

import java.util.List;

/**
 * The {@code .bot} command tree: the short, direct front end for the autonomous bot.
 *
 * <p>It is a thin wrapper over the same {@link BotModule} methods the dashboard and HUD use, so a
 * command never diverges from the GUI. Every branch resolves the live module through
 * {@link LogCommands} (never a cached instance), validates its input, and explains a failure in
 * plain language instead of throwing.
 */
public class BotCommand extends Command {
    public BotCommand() {
        super("bot", "Autonomous bot: start, stop, tasks, waypoints, profiles.", "b");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            status();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("status").executes(context -> {
            status();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("start").executes(context -> {
            action("start");
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("pause").executes(context -> {
            action("pause");
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("resume").executes(context -> {
            action("resume");
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("stop").executes(context -> {
            action("stop");
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("emergency").executes(context -> {
            BotModule bot = LogCommands.bot();
            if (bot == null) {
                error("The bot module is not available.");
                return SINGLE_SUCCESS;
            }
            bot.emergencyStop();
            info("Emergency stop engaged; the queue is cleared.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("goto")
            .then(argument("target", StringArgumentType.greedyString())
                .executes(context -> {
                    goTo(StringArgumentType.getString(context, "target"));
                    return SINGLE_SUCCESS;
                })));

        builder.then(literal("tasks").executes(context -> {
            tasks();
            return SINGLE_SUCCESS;
        }).then(literal("clear").executes(context -> {
            BotModule bot = LogCommands.bot();
            if (bot == null) {
                error("The bot module is not available.");
                return SINGLE_SUCCESS;
            }
            int before = bot.controller().queue().size();
            bot.controller().queue().clear();
            info("Cleared %d queued task%s.", before, before == 1 ? "" : "s");
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("waypoint")
            .then(literal("add")
                .then(argument("name", StringArgumentType.word()).executes(context -> {
                    addWaypoint(StringArgumentType.getString(context, "name"));
                    return SINGLE_SUCCESS;
                })))
            .then(literal("del")
                .then(argument("name", StringArgumentType.word()).executes(context -> {
                    delWaypoint(StringArgumentType.getString(context, "name"));
                    return SINGLE_SUCCESS;
                })))
            .then(literal("list").executes(context -> {
                    listWaypoints();
                    return SINGLE_SUCCESS;
            })));

        builder.then(literal("profile")
            .executes(context -> {
                listProfiles();
                return SINGLE_SUCCESS;
            })
            .then(literal("list").executes(context -> {
                listProfiles();
                return SINGLE_SUCCESS;
            }))
            .then(literal("apply")
                .then(argument("name", StringArgumentType.greedyString()).executes(context -> {
                    BotModule bot = LogCommands.bot();
                    if (bot == null) {
                        error("The bot module is not available.");
                        return SINGLE_SUCCESS;
                    }
                    bot.applyProfile(StringArgumentType.getString(context, "name"));
                    return SINGLE_SUCCESS;
                })))
            .then(literal("save")
                .then(argument("name", StringArgumentType.greedyString()).executes(context -> {
                    BotModule bot = LogCommands.bot();
                    if (bot == null) {
                        error("The bot module is not available.");
                        return SINGLE_SUCCESS;
                    }
                    bot.saveProfile(StringArgumentType.getString(context, "name"));
                    return SINGLE_SUCCESS;
                })))
            .then(literal("delete")
                .then(argument("name", StringArgumentType.greedyString()).executes(context -> {
                    BotModule bot = LogCommands.bot();
                    if (bot == null) {
                        error("The bot module is not available.");
                        return SINGLE_SUCCESS;
                    }
                    bot.deleteProfile(StringArgumentType.getString(context, "name"));
                    return SINGLE_SUCCESS;
                }))));
    }

    /**
     * Queues a navigation task to a coordinate or a saved waypoint.
     *
     * <p>Accepts {@code "100 64 -20"} for explicit coordinates or a single waypoint name. The
     * destination is carried in the task's {@code x}/{@code y}/{@code z} parameters, which is what
     * the navigation executor reads; a waypoint name is carried as the task's item id.
     */
    private void goTo(String target) {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        if (target == null || target.isBlank()) {
            error("Give coordinates (x y z) or a saved waypoint name.");
            return;
        }

        String[] parts = target.trim().split("\\s+");
        BotTask task;
        String what;
        if (parts.length >= 2) {
            try {
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[parts.length - 1]);
                int y = parts.length >= 3
                    ? Integer.parseInt(parts[1])
                    : (mc.player == null ? 64 : (int) Math.floor(mc.player.getY()));
                task = new BotTask(TaskType.NAVIGATION, "goto")
                    .label("Go to " + x + ", " + z)
                    .setParameter("x", Integer.toString(x))
                    .setParameter("y", Integer.toString(y))
                    .setParameter("z", Integer.toString(z));
                what = x + ", " + y + ", " + z;
            } catch (NumberFormatException e) {
                error("Those are not valid coordinates. Use \"x y z\" or a waypoint name.");
                return;
            }
        } else {
            if (bot.memory().waypoint(parts[0]).isEmpty()) {
                error("No waypoint named \"%s\".", parts[0]);
                return;
            }
            task = new BotTask(TaskType.NAVIGATION, "goto")
                .label("Go to waypoint " + parts[0])
                .itemId(parts[0]);
            what = "waypoint " + parts[0];
        }

        if (bot.controller().queue().add(task)) {
            bot.startBot();
            info("Heading to %s.", what);
        } else {
            error("The queue is full; remove a task first.");
        }
    }

    private void status() {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        info("Automation: %s", bot.statusLine());
        info("Queue: %d, navigation: %s.", bot.controller().queue().size(), bot.navigationBackend());
        BlockPos3 target = bot.navTarget();
        if (target != null) {
            info("Destination: %s (%.1f blocks away).", target, bot.navDistance());
        }
    }

    private void action(String action) {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        boolean ok = switch (action) {
            case "start" -> bot.startBot();
            case "pause" -> bot.pauseBot();
            case "resume" -> bot.resumeBot();
            case "stop" -> bot.stopBot();
            default -> false;
        };
        String past = switch (action) {
            case "start" -> "started";
            case "pause" -> "paused";
            case "resume" -> "resumed";
            case "stop" -> "stopped";
            default -> action + "ed";
        };
        if (ok) {
            info("Automation %s.", past);
        } else {
            warning("Automation could not %s from its current state (%s).", action, bot.controller().state());
        }
    }

    private void tasks() {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        var queue = bot.controller().queue();
        if (queue.isEmpty()) {
            info("The task queue is empty.");
        } else {
            info("%d queued task%s:", queue.size(), queue.size() == 1 ? "" : "s");
            int shown = 0;
            for (BotTask task : queue.tasks()) {
                if (shown++ >= 20) {
                    info("  ... and %d more.", queue.size() - 20);
                    break;
                }
                info("  #%d %s %s (%d/%d) %s", task.id(), task.type(),
                    task.label().isBlank() ? task.itemId() : task.label(),
                    task.progress(), task.required(), task.state());
            }
        }
    }

    private void addWaypoint(String name) {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        if (mc.player == null) {
            error("Join a world before saving a waypoint.");
            return;
        }
        BlockPos3 pos = new BlockPos3((int) Math.floor(mc.player.getX()),
            (int) Math.floor(mc.player.getY()), (int) Math.floor(mc.player.getZ()));
        String dimension = mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
        BotMemory.Waypoint waypoint = new BotMemory.Waypoint(name, dimension, pos.x(), pos.y(), pos.z());
        bot.memory().putWaypoint(waypoint);
        bot.persistMemory();
        info("Saved waypoint \"%s\" at %s.", waypoint.name(), pos);
    }

    private void delWaypoint(String name) {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        if (bot.memory().removeWaypoint(name)) {
            bot.persistMemory();
            info("Removed waypoint \"%s\".", name);
        } else {
            error("No waypoint named \"%s\".", name);
        }
    }

    private void listWaypoints() {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        List<BotMemory.Waypoint> waypoints = bot.memory().waypoints();
        if (waypoints.isEmpty()) {
            info("No waypoints saved.");
            return;
        }
        info("%d waypoint%s:", waypoints.size(), waypoints.size() == 1 ? "" : "s");
        for (BotMemory.Waypoint waypoint : waypoints) {
            info("  %s %s", waypoint.name(), waypoint.describe());
        }
    }

    private void listProfiles() {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        List<BotProfile> profiles = bot.profiles().all();
        if (profiles.isEmpty()) {
            info("No profiles available.");
            return;
        }
        info("%d profile%s:", profiles.size(), profiles.size() == 1 ? "" : "s");
        for (BotProfile profile : profiles) {
            info("  %s%s (%d settings)", profile.name(), profile.builtin() ? " [built-in]" : "",
                profile.size());
        }
    }
}
