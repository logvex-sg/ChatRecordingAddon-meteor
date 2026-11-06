package com.logvex.addon.command;

import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.notify.NotificationModule;
import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.schematicdropper.SchematicDropperModule;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.logvex.addon.framework.commands.Command;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import java.util.List;
import java.util.Locale;

/**
 * The {@code .log} command tree: the text front end for the whole addon.
 *
 * <p>It is deliberately read-mostly. Anything that changes state (recording, the bot, notifications,
 * the schematic loader) routes through the same public methods the GUI and HUD use, so a command can
 * never drift from what the rest of the addon does. Every branch validates its input and explains
 * failures in plain language rather than throwing.
 */
public class LogCommand extends Command {
    public LogCommand() {
        super("log", "Log Addon: status, modules, tasks, notifications, schematics and stats.", "la");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            help();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("help").executes(context -> {
            help();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("status").executes(context -> {
            status();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("gui").executes(context -> {
            openGui();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("modules").executes(context -> {
            modules();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("stats").executes(context -> {
            stats();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("notifications")
            .executes(context -> {
                notifications(10);
                return SINGLE_SUCCESS;
            })
            .then(argument("count", IntegerArgumentType.integer(1, 100)).executes(context -> {
                notifications(IntegerArgumentType.getInteger(context, "count"));
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                NotificationModule module = LogCommands.notifications();
                if (module == null) {
                    error("The notifications module is not available.");
                    return SINGLE_SUCCESS;
                }
                module.center().clear();
                info("Notification history cleared.");
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("tasks").executes(context -> {
            tasks();
            return SINGLE_SUCCESS;
        }).then(literal("list").executes(context -> {
            tasks();
            return SINGLE_SUCCESS;
        })).then(literal("pause").executes(context -> {
            botAction("pause");
            return SINGLE_SUCCESS;
        })).then(literal("resume").executes(context -> {
            botAction("resume");
            return SINGLE_SUCCESS;
        })).then(literal("start").executes(context -> {
            botAction("start");
            return SINGLE_SUCCESS;
        })).then(literal("stop").executes(context -> {
            botAction("stop");
            return SINGLE_SUCCESS;
        })).then(literal("clear").executes(context -> {
            BotModule bot = LogCommands.bot();
            if (bot == null) {
                error("The bot module is not available.");
                return SINGLE_SUCCESS;
            }
            int before = bot.controller().queue().size();
            bot.controller().queue().clear();
            info("Cleared %d queued task%s.", before, before == 1 ? "" : "s");
            return SINGLE_SUCCESS;
        })).then(literal("add")
            .then(argument("type", StringArgumentType.word())
                .then(argument("item", StringArgumentType.word())
                    .executes(context -> {
                        addTask(StringArgumentType.getString(context, "type"),
                            StringArgumentType.getString(context, "item"), 1);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("amount", IntegerArgumentType.integer(1, 1_000_000)).executes(context -> {
                        addTask(StringArgumentType.getString(context, "type"),
                            StringArgumentType.getString(context, "item"),
                            IntegerArgumentType.getInteger(context, "amount"));
                        return SINGLE_SUCCESS;
                    })))))
        );

        builder.then(literal("waypoints").executes(context -> {
            waypoints();
            return SINGLE_SUCCESS;
        }).then(literal("list").executes(context -> {
            waypoints();
            return SINGLE_SUCCESS;
        })).then(literal("add").then(argument("name", StringArgumentType.greedyString()).executes(context -> {
            addWaypoint(StringArgumentType.getString(context, "name"));
            return SINGLE_SUCCESS;
        }))));

        builder.then(literal("import")
            .executes(context -> {
                info("Usage: (highlight).log import <file>(default).");
                return SINGLE_SUCCESS;
            })
            .then(argument("path", StringArgumentType.greedyString()).executes(context -> {
                importMaterials(StringArgumentType.getString(context, "path"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("schematic").executes(context -> {
            schematicStatus();
            return SINGLE_SUCCESS;
        }).then(literal("status").executes(context -> {
            schematicStatus();
            return SINGLE_SUCCESS;
        })).then(literal("load").then(argument("path", StringArgumentType.greedyString()).executes(context -> {
            loadSchematic(StringArgumentType.getString(context, "path"));
            return SINGLE_SUCCESS;
        }))).then(literal("start").executes(context -> {
            schematicAction("start");
            return SINGLE_SUCCESS;
        })).then(literal("pause").executes(context -> {
            schematicAction("pause");
            return SINGLE_SUCCESS;
        })).then(literal("resume").executes(context -> {
            schematicAction("resume");
            return SINGLE_SUCCESS;
        })).then(literal("stop").executes(context -> {
            schematicAction("stop");
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("record")
            .executes(context -> {
                recording(true);
                return SINGLE_SUCCESS;
            })
            .then(literal("on").executes(context -> {
                recording(true);
                return SINGLE_SUCCESS;
            }))
            .then(literal("off").executes(context -> {
                recording(false);
                return SINGLE_SUCCESS;
            }))
        );
    }

    // ------------------------------------------------------------------ sections

    private void help() {
        info("Log Addon commands:");
        info("  (highlight).log gui(default) - open the addon window.");
        info("  (highlight).log status(default) - overall status.");
        info("  (highlight).log modules(default) - list Log Addon modules.");
        info("  (highlight).log stats(default) - session statistics.");
        info("  (highlight).log record <on|off>(default) - toggle chat recording.");
        info("  (highlight).log notifications [count|clear](default) - recent notifications.");
        info("  (highlight).log tasks [list|start|pause|resume|stop|clear](default) - the bot queue.");
        info("  (highlight).log tasks add <type> <item> [amount](default) - queue a task.");
        info("  (highlight).log waypoints [list|add <name>](default) - bot waypoints.");
        info("  (highlight).log import <file>(default) - import a material list.");
        info("  (highlight).log schematic [status|load <path>|start|pause|resume|stop](default).");
    }

    private void status() {
        ChatLoggerModule logger = LogCommands.logger();
        if (logger != null) {
            info("Chat logging: %s, %s, %s",
                logger.isRecording() ? "recording" : "stopped",
                logger.storageSummary(),
                logger.isActive() ? "module on" : "module off");
        } else {
            warning("Chat logger module not found.");
        }

        BotModule bot = LogCommands.bot();
        if (bot != null) {
            info("Automation: %s", bot.statusLine());
        }

        NotificationModule notifications = LogCommands.notifications();
        if (notifications != null) {
            info("Notifications: %d stored, %d accepted, %d suppressed",
                notifications.center().size(), notifications.center().accepted(),
                notifications.center().suppressed());
        }
    }

    private void modules() {
        List<Module> modules = LogCommands.addonModules();
        if (modules.isEmpty()) {
            warning("No Log Addon modules are registered.");
            return;
        }
        info("%d Log Addon module%s:", modules.size(), modules.size() == 1 ? "" : "s");
        for (Module module : modules) {
            info("  %s %s - %s", module.isActive() ? "(highlight)[on](default)" : "(highlight)[off](default)",
                module.title, module.description);
        }
    }

    private void stats() {
        ChatLoggerModule logger = LogCommands.logger();
        if (logger == null) {
            error("The chat logger module is not available.");
            return;
        }
        StatsSnapshot snapshot = logger.service().snapshot();
        if (snapshot.totalMessages() == 0 && snapshot.durationMs() == 0) {
            info("No session statistics yet.");
            return;
        }
        info("Messages: %d over %s (%.1f/min)", snapshot.totalMessages(),
            ReportFormatting.duration(snapshot.durationMs()), snapshot.messagesPerMinute());
        info("Players seen: %d, joins/leaves: %d/%d, deaths: %d", snapshot.uniquePlayers(),
            snapshot.joinEvents(), snapshot.leaveEvents(), snapshot.deathEvents());
        info("Keyword alerts: %d", snapshot.keywordAlerts());
        if (!snapshot.topPlayers().isEmpty()) {
            info("Most active: %s", String.join(", ", snapshot.topPlayers()));
        }
    }

    private void notifications(int count) {
        NotificationModule module = LogCommands.notifications();
        if (module == null) {
            error("The notifications module is not available.");
            return;
        }
        List<Notification> recent = module.center().recent(count);
        if (recent.isEmpty()) {
            info("No notifications recorded.");
            return;
        }
        info("Most recent %d notification%s:", recent.size(), recent.size() == 1 ? "" : "s");
        for (Notification notification : recent) {
            info("  [%s] %s", notification.priority().name().toLowerCase(Locale.ROOT), notification.line());
        }
    }

    private void tasks() {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        var queue = bot.controller().queue();
        info("Automation: %s", bot.statusLine());
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
        var history = bot.controller().history();
        info("History: %d recorded, %d succeeded, %d failed",
            history.size(), history.successCount(), history.failureCount());
    }

    private void botAction(String action) {
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
        if (ok) info("Automation %s.", action.equals("start") ? "started" : action + "d");
        else warning("Automation could not %s from its current state (%s).", action, bot.controller().state());
    }

    private void addTask(String typeName, String item, int amount) {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        TaskType type = TaskArgs.parseType(typeName);
        if (type == null) {
            error("Unknown task type \"%s\". Try one of: %s", typeName, TaskArgs.typeNames());
            return;
        }
        String itemId = TaskArgs.normaliseItem(item);
        if (itemId.isEmpty()) {
            error("Give an item, for example oak_log or minecraft:oak_log.");
            return;
        }
        BotTask task = new BotTask(type, itemId).itemId(itemId).required(amount);
        if (bot.controller().queue().add(task)) {
            info("Queued %s x%d.", itemId, amount);
        } else {
            error("The queue is full; remove a task first.");
        }
    }

    private void waypoints() {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        List<BotMemory.Waypoint> waypoints = bot.memory().waypoints();
        if (waypoints.isEmpty()) {
            info("No waypoints recorded. Stand somewhere and use (highlight).log waypoints add <name>(default).");
            return;
        }
        info("%d waypoint%s:", waypoints.size(), waypoints.size() == 1 ? "" : "s");
        for (BotMemory.Waypoint waypoint : waypoints) {
            info("  %s - %s", waypoint.name(), waypoint.describe());
        }
    }

    private void addWaypoint(String name) {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        if (mc.player == null) {
            error("You must be in a world to record a waypoint.");
            return;
        }
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            error("Give the waypoint a name.");
            return;
        }
        BotMemory.Waypoint waypoint = new BotMemory.Waypoint(clean,
            mc.player.getWorld().getRegistryKey().getValue().toString(),
            (int) Math.floor(mc.player.getX()),
            (int) Math.floor(mc.player.getY()),
            (int) Math.floor(mc.player.getZ()));
        bot.memory().putWaypoint(waypoint);
        bot.persistMemory();
        info("Saved waypoint %s at %s.", clean, waypoint.describe());
    }

    private void schematicStatus() {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        if (module == null) {
            error("The schematic dropper module is not available.");
            return;
        }
        if (!module.buildController().hasSchematic()) {
            info("No schematic loaded. Use (highlight).log schematic load <path>(default).");
            return;
        }
        com.logvex.addon.schematicdropper.core.ProgressSnapshot snapshot = module.snapshot();
        info("Build: %s, %d/%d dropped (%s)", module.runState(),
            snapshot.confirmedDropped(), snapshot.totalRequired(),
            com.logvex.addon.common.Messages.percent(snapshot.percent()));
    }

    private void importMaterials(String path) {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        if (module == null) {
            error("The schematic dropper module is not available.");
            return;
        }
        module.importMaterialFromPath(path);
    }

    private void loadSchematic(String path) {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        if (module == null) {
            error("The schematic dropper module is not available.");
            return;
        }
        module.loadSchematicFromPath(path);
    }

    private void schematicAction(String action) {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        if (module == null) {
            error("The schematic dropper module is not available.");
            return;
        }
        switch (action) {
            case "start" -> module.startBuild();
            case "pause" -> module.pauseBuild();
            case "resume" -> module.resumeBuild();
            case "stop" -> module.stopBuild();
            default -> warning("Unknown action \"%s\".", action);
        }
    }

    private void openGui() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        client.setScreen(new com.logvex.addon.gui.ClickGuiScreen());
        info("Opened the Log Addon window.");
    }

    private void recording(boolean on) {
        ChatLoggerModule logger = LogCommands.logger();
        if (logger == null) {
            error("The chat logger module is not available.");
            return;
        }
        if (on) {
            if (!logger.isActive()) logger.toggle();
            logger.startRecording();
            info("Recording started.");
        } else {
            logger.stopRecording();
            info("Recording stopped.");
        }
    }
}
