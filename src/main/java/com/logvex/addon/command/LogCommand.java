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
        super("log", "Log Client: status, modules, tasks, notifications, schematics and stats.", "la");
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

        builder.then(literal("world")
            .executes(context -> {
                worldStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                worldStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("map").executes(context -> {
                openWorldMap();
                return SINGLE_SUCCESS;
            }))
            .then(literal("places").executes(context -> {
                worldPlaces("");
                return SINGLE_SUCCESS;
            }).then(argument("query", StringArgumentType.greedyString()).executes(context -> {
                worldPlaces(StringArgumentType.getString(context, "query"));
                return SINGLE_SUCCESS;
            })))
            .then(literal("nearest").executes(context -> {
                worldNearest(null);
                return SINGLE_SUCCESS;
            }).then(argument("category", StringArgumentType.word()).executes(context -> {
                worldNearest(StringArgumentType.getString(context, "category"));
                return SINGLE_SUCCESS;
            })))
            .then(literal("mark")
                .then(argument("name", StringArgumentType.greedyString()).executes(context -> {
                    worldMark(StringArgumentType.getString(context, "name"));
                    return SINGLE_SUCCESS;
                })))
            .then(literal("export").executes(context -> {
                worldExport();
                return SINGLE_SUCCESS;
            }))
        );

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

        builder.then(literal("importlog")
            .executes(context -> {
                info("Usage: (highlight).log importlog <file>(default).");
                return SINGLE_SUCCESS;
            })
            .then(argument("path", StringArgumentType.greedyString()).executes(context -> {
                importLog(StringArgumentType.getString(context, "path"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("alerts")
            .executes(context -> {
                alerts(10);
                return SINGLE_SUCCESS;
            })
            .then(argument("count", IntegerArgumentType.integer(1, 100)).executes(context -> {
                alerts(IntegerArgumentType.getInteger(context, "count"));
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                com.logvex.addon.chatlog.mc.LogService.get().alerts().clear();
                info("Alert history cleared.");
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("playtime").executes(context -> {
            playtime("");
            return SINGLE_SUCCESS;
        }).then(argument("player", StringArgumentType.greedyString()).executes(context -> {
            playtime(StringArgumentType.getString(context, "player"));
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
        })).then(literal("acquire").executes(context -> {
            acquisitionAction("start");
            return SINGLE_SUCCESS;
        })).then(literal("import").then(argument("path", StringArgumentType.greedyString()).executes(context -> {
            importMaterials(StringArgumentType.getString(context, "path"));
            return SINGLE_SUCCESS;
        }))));

        builder.then(literal("acquire").executes(context -> {
            acquisitionAction("start");
            return SINGLE_SUCCESS;
        }).then(literal("start").executes(context -> {
            acquisitionAction("start");
            return SINGLE_SUCCESS;
        })).then(literal("pause").executes(context -> {
            acquisitionAction("pause");
            return SINGLE_SUCCESS;
        })).then(literal("resume").executes(context -> {
            acquisitionAction("resume");
            return SINGLE_SUCCESS;
        })).then(literal("stop").executes(context -> {
            acquisitionAction("stop");
            return SINGLE_SUCCESS;
        })).then(literal("status").executes(context -> {
            acquisitionAction("status");
            return SINGLE_SUCCESS;
        })).then(literal("retry").executes(context -> {
            acquisitionAction("retry");
            return SINGLE_SUCCESS;
        })).then(literal("import").then(argument("path", StringArgumentType.greedyString()).executes(context -> {
            importMaterials(StringArgumentType.getString(context, "path"));
            return SINGLE_SUCCESS;
        }))));

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

        builder.then(literal("digest").executes(context -> {
            digest();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("timeline")
            .executes(context -> {
                timeline("");
                return SINGLE_SUCCESS;
            })
            .then(argument("filter", StringArgumentType.greedyString()).executes(context -> {
                timeline(StringArgumentType.getString(context, "filter"));
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("filter")
            .executes(context -> {
                filterStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("stats").executes(context -> {
                filterStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("reset").executes(context -> {
                filterReset();
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("notes")
            .executes(context -> {
                notes("");
                return SINGLE_SUCCESS;
            })
            .then(literal("add").then(argument("player", StringArgumentType.word())
                .then(argument("text", StringArgumentType.greedyString()).executes(context -> {
                    notesAdd(StringArgumentType.getString(context, "player"),
                        StringArgumentType.getString(context, "text"));
                    return SINGLE_SUCCESS;
                }))))
            .then(literal("remove").then(argument("player", StringArgumentType.word()).executes(context -> {
                notesRemove(StringArgumentType.getString(context, "player"));
                return SINGLE_SUCCESS;
            })))
            .then(literal("list").executes(context -> {
                notes("");
                return SINGLE_SUCCESS;
            }))
            .then(literal("search").then(argument("filter", StringArgumentType.greedyString()).executes(context -> {
                notes(StringArgumentType.getString(context, "filter"));
                return SINGLE_SUCCESS;
            })))
        );

        builder.then(literal("identities")
            .executes(context -> {
                identities("");
                return SINGLE_SUCCESS;
            })
            .then(literal("list").executes(context -> {
                identities("");
                return SINGLE_SUCCESS;
            }))
            .then(literal("renamed").executes(context -> {
                renames();
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                com.logvex.addon.chatlog.mc.LogService.get().identities().clear();
                com.logvex.addon.chatlog.mc.PlayerIdentityService.get().saveAsync();
                info("Identity index cleared.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("watch")
                .then(argument("player", StringArgumentType.word()).executes(context -> {
                    watch(StringArgumentType.getString(context, "player"), true);
                    return SINGLE_SUCCESS;
                })))
            .then(literal("unwatch")
                .then(argument("player", StringArgumentType.word()).executes(context -> {
                    watch(StringArgumentType.getString(context, "player"), false);
                    return SINGLE_SUCCESS;
                })))
            .then(literal("watchlist").executes(context -> {
                watchlist();
                return SINGLE_SUCCESS;
            }))
            .then(argument("filter", StringArgumentType.greedyString()).executes(context -> {
                identities(StringArgumentType.getString(context, "filter"));
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("sessions")
            .executes(context -> {
                sessions();
                return SINGLE_SUCCESS;
            })
            .then(literal("list").executes(context -> {
                sessions();
                return SINGLE_SUCCESS;
            }))
            .then(literal("clear").executes(context -> {
                com.logvex.addon.chatlog.mc.LogService.get().sessionHistory().clear();
                info("Session history cleared.");
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("update")
            .executes(context -> {
                update();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                update();
                return SINGLE_SUCCESS;
            }))
            .then(literal("check").executes(context -> {
                updateCheck();
                return SINGLE_SUCCESS;
            }))
            .then(literal("download").executes(context -> {
                updateDownload();
                return SINGLE_SUCCESS;
            }))
            .then(literal("gui").executes(context -> {
                updateGui();
                return SINGLE_SUCCESS;
            }))
            .then(literal("channel").then(argument("channel", StringArgumentType.word()).executes(context -> {
                updateChannel(StringArgumentType.getString(context, "channel"));
                return SINGLE_SUCCESS;
            })))
        );

        builder.then(literal("web").executes(context -> {
            openWebConsole();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("companion")
            .executes(context -> {
                companion();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                companion();
                return SINGLE_SUCCESS;
            }))
            .then(literal("start").executes(context -> {
                companionSet(true);
                return SINGLE_SUCCESS;
            }))
            .then(literal("stop").executes(context -> {
                companionSet(false);
                return SINGLE_SUCCESS;
            }))
            .then(literal("token").executes(context -> {
                companionToken();
                return SINGLE_SUCCESS;
            }))
            .then(literal("regenerate").executes(context -> {
                companionRegenerate();
                return SINGLE_SUCCESS;
            }))
            .then(literal("gui").executes(context -> {
                companionGui();
                return SINGLE_SUCCESS;
            }))
        );
    }

    // ------------------------------------------------------------------ sections

    private void help() {
        info("Log Client commands:");
        info("  (highlight).log gui(default) - open the addon window.");
        info("  (highlight).log status(default) - overall status.");
        info("  (highlight).log modules(default) - list Log Client modules.");
        info("  (highlight).log stats(default) - session statistics.");
        info("  (highlight).log record <on|off>(default) - toggle chat recording.");
        info("  (highlight).log notifications [count|clear](default) - recent notifications.");
        info("  (highlight).log tasks [list|start|pause|resume|stop|clear](default) - the bot queue.");
        info("  (highlight).log tasks add <type> <item> [amount](default) - queue a task.");
        info("  (highlight).log waypoints [list|add <name>](default) - bot waypoints.");
        info("  (highlight).log import <file>(default) - import a material list.");
        info("  (highlight).log importlog <file>(default) - import a chat log into the archive.");
        info("  (highlight).log alerts [count|clear](default) - recent keyword/webhook/error alerts.");
        info("  (highlight).log playtime [player](default) - observed player session durations.");
        info("  (highlight).log schematic [status|load <path>|start|pause|resume|stop](default).");
        info("  (highlight).log acquire [start|pause|resume|stop|status|retry|import <path>](default) - Creative material acquisition.");
        info("  (highlight).log digest(default) - summarise recent chat activity.");
        info("  (highlight).log timeline [filter](default) - recent session milestones.");
        info("  (highlight).log filter [stats|reset](default) - chat filter status.");
        info("  (highlight).log notes [list|add <player> <text>|remove <player>|search <filter>](default).");
        info("  (highlight).log identities [renamed|watch|unwatch|watchlist|clear|<filter>](default) - observed name changes.");
        info("  (highlight).log sessions [list|clear](default) - past recording sessions.");
        info("  (highlight).log companion [status|start|stop|token|regenerate|gui](default) - the companion web app.");
        info("  (highlight).log update [status|check|download|channel <stable|beta>|gui](default) - GitHub release updates.");
        info("  (highlight).log world [status|map|places [query]|nearest [category]|mark <name>|export](default) - world intelligence.");
        info("  (highlight).log web(default) - open the local web console in your browser.");
        info("  (highlight).ai [status|triggers|facts|remember|forget|test|gui](default) - the chat assistant.");
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
            warning("No Log Client modules are registered.");
            return;
        }
        info("%d Log Client module%s:", modules.size(), modules.size() == 1 ? "" : "s");
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

    private void alerts(int count) {
        var log = com.logvex.addon.chatlog.mc.LogService.get().alerts();
        List<com.logvex.addon.chatlog.core.AlertLog.Alert> recent = log.recent("", count);
        if (recent.isEmpty()) {
            info("No alerts recorded yet.");
            return;
        }
        info("Most recent %d alert%s:", recent.size(), recent.size() == 1 ? "" : "s");
        for (var alert : recent) {
            info("  [%s] %s: %s", alert.kind().id(), alert.title(),
                Messages.truncate(alert.detail(), 90));
        }
        info("Totals: %d keyword, %d webhook, %d error.",
            log.count(com.logvex.addon.chatlog.core.AlertLog.Kind.KEYWORD),
            log.count(com.logvex.addon.chatlog.core.AlertLog.Kind.WEBHOOK),
            log.count(com.logvex.addon.chatlog.core.AlertLog.Kind.ERROR));
    }

    private void playtime(String filter) {
        var sessions = com.logvex.addon.chatlog.mc.LogService.get().sessions();
        List<com.logvex.addon.chatlog.core.PlayerSession> list = sessions.search(filter);
        if (list.isEmpty()) {
            info(filter.isBlank() ? "No player sessions recorded yet."
                : "No player sessions match that name.");
            return;
        }
        long now = System.currentTimeMillis();
        int shown = Math.min(list.size(), 20);
        info("%d player session%s%s:", list.size(), list.size() == 1 ? "" : "s",
            filter.isBlank() ? "" : " matching '" + filter + "'");
        for (int i = 0; i < shown; i++) {
            var session = list.get(i);
            String duration = session.hasMeasuredTime()
                ? ReportFormatting.duration(session.onlineMs(now))
                : "unknown";
            info("  %s - %s across %d session%s%s", session.name(), duration, session.sessions(),
                session.sessions() == 1 ? "" : "s", session.online() ? " (online)" : "");
        }
        if (list.size() > shown) info("  ... and %d more.", list.size() - shown);
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
        if (!module.isActive()) {
            warning("Enable Schematic Dropper before importing, so the queue persists.");
        }
        module.importMaterialFromPath(path);
    }

    private void importLog(String path) {
        ChatLoggerModule logger = LogCommands.logger();
        if (logger == null || logger.storage() == null) {
            error("Storage is unavailable. Enable the Chat Logger module first.");
            return;
        }
        java.nio.file.Path file;
        try {
            file = java.nio.file.Path.of(path);
        } catch (RuntimeException e) {
            error("That path is not valid.");
            return;
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            error("No file found at that path.");
            return;
        }
        info("Reading (highlight)%s(default)...", file.getFileName());
        logger.storage().importLogAsync(file, result -> {
            if (result.isEmpty()) {
                warning("No importable entries were found%s.",
                    result.skipped().isEmpty() ? "" : " (" + result.skipped().size() + " lines skipped)");
                return;
            }
            info("%s", result.describe(file.getFileName().toString()));
            logger.storage().importEntriesAsync(result.entries(),
                () -> info("Imported %s into the archive.", Messages.plural(result.entries().size(), "entry", "entries")));
        }, message -> error("Could not read the file: %s", message));
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

    /**
     * Drives the automatic Creative acquisition workflow: the same public methods the GUI uses, so a
     * command can never take a different path from the interface.
     */
    private void acquisitionAction(String action) {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        if (module == null) {
            error("The schematic dropper module is not available.");
            return;
        }
        switch (action) {
            case "start" -> module.startAcquisition();
            case "pause" -> module.pauseAcquisition();
            case "resume" -> module.resumeAcquisition();
            case "stop" -> module.stopAcquisition();
            case "retry" -> module.retryAcquisition();
            case "status" -> acquisitionStatus(module);
            default -> warning("Unknown action \"%s\".", action);
        }
    }

    private void acquisitionStatus(SchematicDropperModule module) {
        var snapshot = module.acquisitionSnapshot();
        info("Acquisition: %s, %s",
            module.acquisitionState().name().toLowerCase(Locale.ROOT),
            Messages.percent(snapshot.percent() / 100.0));
        info("  %s acquired, %s remaining, %s.",
            Messages.number(snapshot.totalAcquired()), Messages.number(snapshot.totalRemaining()),
            snapshot.failedOperations() == 0 ? "no failures"
                : Messages.plural(snapshot.failedOperations(), "failure"));
        var current = snapshot.current();
        if (current != null) {
            info("  Current: %s (%s required).", Messages.prettyId(current.itemId()),
                Messages.number(current.required()));
        }
    }

    private void openGui() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        client.setScreen(new com.logvex.addon.gui.ClickGuiScreen());
        info("Opened the Log Client window.");
    }

    private void worldStatus() {
        var module = LogCommands.world();
        if (module == null) {
            error("The world-intelligence module is not available.");
            return;
        }
        var profile = module.service().intelligence().current();
        if (profile == null) {
            info("No world is loaded yet. Join a world to start recording discoveries.");
            return;
        }
        info("World: %s", profile.label());
        info("Places: %d, biomes: %d, resource types: %d, chunks: %d",
            profile.locations().size(), profile.biomes().size(),
            profile.resources().size(), profile.chunks().size());
        var recent = profile.discoveries().recent(5);
        if (recent.isEmpty()) {
            info("No discoveries recorded yet.");
        } else {
            info("Recent discoveries:");
            for (var event : recent) {
                info("  %s %s at %s", event.kind(), event.label(),
                    com.logvex.addon.world.core.WorldFormat.coords(event.x(), event.y(), event.z()));
            }
        }
    }

    private void openWorldMap() {
        var module = LogCommands.world();
        if (module == null) {
            error("The world-intelligence module is not available.");
            return;
        }
        module.openMap();
    }

    private void worldPlaces(String query) {
        var module = LogCommands.world();
        if (module == null) {
            error("The world-intelligence module is not available.");
            return;
        }
        var places = module.service().intelligence().search(query, null, null);
        if (places.isEmpty()) {
            info(query.isBlank() ? "No places recorded yet." : "No places match \"" + query + "\".");
            return;
        }
        info("Found %d place%s:", places.size(), places.size() == 1 ? "" : "s");
        int shown = 0;
        for (var point : places) {
            if (shown++ >= 20) {
                info("  ... and %d more.", places.size() - 20);
                break;
            }
            info("  %s (%s) %s", point.name().isBlank() ? "(unnamed)" : point.name(),
                point.category(), com.logvex.addon.world.core.WorldFormat.coords(point.x(), point.y(), point.z()));
        }
    }

    private void worldNearest(String categoryRaw) {
        var module = LogCommands.world();
        if (module == null) {
            error("The world-intelligence module is not available.");
            return;
        }
        if (mc.player == null || mc.world == null) {
            error("Join a world first.");
            return;
        }
        com.logvex.addon.world.core.LocationCategory category = categoryRaw == null
            ? null : com.logvex.addon.world.core.LocationCategory.fromName(categoryRaw);
        String dimension = mc.world.getRegistryKey().getValue().toString();
        var points = module.service().intelligence().nearest(dimension, mc.player.getBlockX(),
            mc.player.getBlockY(), mc.player.getBlockZ(), 5);
        if (category != null) {
            points = points.stream().filter(p -> p.category() == category).toList();
        }
        if (points.isEmpty()) {
            info("No known places nearby.");
            return;
        }
        info("Nearest known places:");
        for (var point : points) {
            double dx = point.x() - mc.player.getX();
            double dy = point.y() - mc.player.getY();
            double dz = point.z() - mc.player.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            info("  %s - %s", point.name().isBlank() ? point.category().toString() : point.name(),
                com.logvex.addon.world.core.WorldFormat.distance(distance));
        }
    }

    private void worldMark(String name) {
        var module = LogCommands.world();
        if (module == null) {
            error("The world-intelligence module is not available.");
            return;
        }
        if (name == null || name.isBlank()) {
            error("Give the place a name.");
            return;
        }
        var point = module.service().markHere(name, com.logvex.addon.world.core.LocationCategory.WAYPOINT);
        if (point == null) {
            error("Join a world before marking a place.");
            return;
        }
        info("Marked \"%s\" at %s.", point.name(),
            com.logvex.addon.world.core.WorldFormat.coords(point.x(), point.y(), point.z()));
    }

    private void worldExport() {
        var module = LogCommands.world();
        if (module == null) {
            error("The world-intelligence module is not available.");
            return;
        }
        java.nio.file.Path file = com.logvex.addon.framework.LogClientContext.FOLDER().toPath()
            .resolve("logclient").resolve("world-intelligence-export.json");
        boolean ok = com.logvex.addon.world.core.WorldStore.exportTo(file,
            module.service().intelligence().exportMap());
        if (ok) info("Exported the world database to %s.", file.getFileName());
        else error("Could not write the export file.");
    }

    private void digest() {
        com.logvex.addon.chatlog.modules.ChatDigestModule module =
            LogCommands.modules().get(com.logvex.addon.chatlog.modules.ChatDigestModule.class);
        if (module == null) {
            error("The chat digest module is not available.");
            return;
        }
        module.digest(true);
    }

    private void timeline(String filter) {
        com.logvex.addon.chatlog.core.SessionTimeline timeline =
            com.logvex.addon.chatlog.mc.SessionTimelineService.get().timeline();
        var events = filter.isBlank() ? timeline.recent(15) : timeline.search(filter);
        if (events.isEmpty()) {
            info(filter.isBlank() ? "No session milestones yet." : "No milestones match that filter.");
            return;
        }
        info("Session milestones: %d", events.size());
        int shown = Math.min(events.size(), 15);
        for (int i = 0; i < shown; i++) {
            var event = events.get(i);
            info("  %s - %s", com.logvex.addon.common.Messages.clock(event.timestamp()), event.line());
        }
        if (events.size() > shown) info("  ... and %d more.", events.size() - shown);
    }

    private void filterStatus() {
        com.logvex.addon.chatlog.mc.ChatFilterService service =
            com.logvex.addon.chatlog.mc.ChatFilterService.get();
        com.logvex.addon.chatlog.core.ChatFilterRuleSet rules = service.rules();
        if (rules.isEmpty()) {
            info("Chat filter: no rules configured (nothing is hidden).");
            return;
        }
        info("Chat filter: %d rules, default %s, %d messages hidden this session.",
            rules.rules().size(), rules.defaultAction().name().toLowerCase(), service.hiddenCount());
    }

    private void filterReset() {
        com.logvex.addon.chatlog.mc.ChatFilterService.get().resetStats();
        info("Chat filter counters reset.");
    }

    private void notes(String filter) {
        var module = LogCommands.modules().get(com.logvex.addon.chatlog.modules.PlayerNotesModule.class);
        if (module == null) {
            error("The player notes module is not available.");
            return;
        }
        var notes = module.notes(filter);
        if (notes.isEmpty()) {
            info(filter.isBlank() ? "No player notes saved." : "No notes match that filter.");
            return;
        }
        info("Player notes: %d", notes.size());
        int shown = Math.min(notes.size(), 15);
        for (int i = 0; i < shown; i++) {
            var note = notes.get(i);
            info("  %s: %s", note.player(), com.logvex.addon.common.Messages.truncate(note.text(), 80));
        }
        if (notes.size() > shown) info("  ... and %d more.", notes.size() - shown);
    }

    private void notesAdd(String player, String text) {
        var module = LogCommands.modules().get(com.logvex.addon.chatlog.modules.PlayerNotesModule.class);
        if (module == null) {
            error("The player notes module is not available.");
            return;
        }
        module.note(player, text);
    }

    private void notesRemove(String player) {
        var module = LogCommands.modules().get(com.logvex.addon.chatlog.modules.PlayerNotesModule.class);
        if (module == null) {
            error("The player notes module is not available.");
            return;
        }
        if (!module.removeNote(player)) warning("No note saved for %s.", player);
    }

    private void identities(String filter) {
        var tracker = com.logvex.addon.chatlog.mc.LogService.get().identities();
        List<com.logvex.addon.chatlog.core.PlayerIdentity> list = tracker.search(filter);
        if (list.isEmpty()) {
            info(filter.isBlank() ? "No account names observed yet."
                : "No observed accounts match that name.");
            return;
        }
        info("Observed accounts: %d%s", list.size(),
            filter.isBlank() ? "" : " matching '" + filter + "'");
        int shown = Math.min(list.size(), 20);
        long now = System.currentTimeMillis();
        for (int i = 0; i < shown; i++) {
            var identity = list.get(i);
            String previous = identity.previousName();
            info("  %s%s - %d name%s, last seen %s", identity.currentName(),
                previous.isEmpty() ? "" : " (was " + previous + ")",
                identity.nameCount(), identity.nameCount() == 1 ? "" : "s",
                Messages.ago(identity.lastSeen(), now));
        }
        if (list.size() > shown) info("  ... and %d more.", list.size() - shown);
    }

    private void renames() {
        var renames = com.logvex.addon.chatlog.mc.LogService.get().identities().renames();
        if (renames.isEmpty()) {
            info("No player renames observed yet.");
            return;
        }
        info("Observed renames: %d", renames.size());
        long now = System.currentTimeMillis();
        int shown = Math.min(renames.size(), 20);
        for (int i = 0; i < shown; i++) {
            var rename = renames.get(i);
            info("  %s \u2192 %s (%s)", rename.from(), rename.to(), Messages.ago(rename.atMs(), now));
        }
        if (renames.size() > shown) info("  ... and %d more.", renames.size() - shown);
    }

    private void watch(String player, boolean add) {
        var watchlist = com.logvex.addon.chatlog.mc.LogService.get().watchlist();
        if (player == null || player.isBlank()) {
            error("Give a player name.");
            return;
        }
        if (add) {
            if (watchlist.add(player)) info("Now watching %s.", Messages.player(player));
            else warning("%s is already on the watchlist.", Messages.player(player));
        } else {
            if (watchlist.remove(player)) info("Stopped watching %s.", Messages.player(player));
            else warning("%s is not on the watchlist.", Messages.player(player));
        }
    }

    private void watchlist() {
        var watchlist = com.logvex.addon.chatlog.mc.LogService.get().watchlist();
        if (watchlist.isEmpty()) {
            info("The watchlist is empty. Use (highlight).log identities watch <player>(default).");
            return;
        }
        info("Watching %d player%s: %s", watchlist.size(), watchlist.size() == 1 ? "" : "s",
            String.join(", ", watchlist.entries()));
    }

    private void sessions() {
        var history = com.logvex.addon.chatlog.mc.LogService.get().sessionHistory();
        if (history.size() == 0) {
            info("No past sessions recorded yet.");
            return;
        }
        info("Sessions kept: %d, %s across %s.", history.size(),
            Messages.plural(history.totalMessages(), "message"),
            Messages.duration(history.totalDurationMs(System.currentTimeMillis())));
        var records = history.search("");
        int shown = Math.min(records.size(), 15);
        for (int i = 0; i < shown; i++) {
            var record = records.get(i);
            info("  %s %s - %s, %s, %d player%s", Messages.dateTime(record.startedAt()),
                record.server(), Messages.duration(record.durationMs(System.currentTimeMillis())),
                Messages.plural(record.messages(), "message"), record.uniquePlayers(),
                record.uniquePlayers() == 1 ? "" : "s");
        }
        if (records.size() > shown) info("  ... and %d more.", records.size() - shown);
    }

    // ------------------------------------------------------------------ companion

    private static com.logvex.addon.companion.mc.CompanionModule companionModule() {
        return Modules.get().get(com.logvex.addon.companion.mc.CompanionModule.class);
    }

    private void companion() {
        var module = companionModule();
        if (module == null) {
            warning("The companion module is not available.");
            return;
        }
        if (!module.isActive()) {
            info("The companion is off. Use .log companion start to turn it on.");
            return;
        }
        if (!module.isRunning()) {
            warning("The companion is on but not listening. Use .log companion start to retry.");
            return;
        }
        info("Companion listening on port %d with %s connected.",
            module.boundPort(), Messages.plural(module.connectedClients(), "app client"));
        info("Address: %s", module.url());
    }

    private void companionSet(boolean on) {
        var module = companionModule();
        if (module == null) {
            warning("The companion module is not available.");
            return;
        }
        if (module.isActive() == on) {
            info("The companion is already %s.", on ? "on" : "off");
            return;
        }
        module.toggle();
        if (on) {
            if (module.isRunning()) {
                info("Companion started on port %d.", module.boundPort());
                info("Open %s in a browser.", module.url());
            } else {
                warning("The companion could not start. Is port %d already in use?",
                    module.settings.get("port") == null ? 6767 : module.settings.get("port").get());
            }
        } else {
            info("Companion stopped.");
        }
    }

    private void companionToken() {
        var module = companionModule();
        if (module == null) {
            warning("The companion module is not available.");
            return;
        }
        info("Companion token: %s", module.accessToken());
        info("Treat this like a password: anyone with it can control this client.");
    }

    private void companionRegenerate() {
        var module = companionModule();
        if (module == null) {
            warning("The companion module is not available.");
            return;
        }
        module.regenerateToken();
        info("A new token was generated. Re-add the client in the app.");
    }

    private void companionGui() {
        var module = companionModule();
        if (module == null) {
            warning("The companion module is not available.");
            return;
        }
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) {
            mc.setScreen(new com.logvex.addon.companion.gui.CompanionScreen(module));
        }
    }

    // ------------------------------------------------------------------ updates

    private com.logvex.addon.update.mc.UpdateModule updateModule() {
        var module = Modules.get().get("updates");
        return module instanceof com.logvex.addon.update.mc.UpdateModule u ? u : null;
    }

    private void update() {
        var snapshot = com.logvex.addon.update.mc.UpdateService.get().snapshot();
        info("Log Client updates - installed %s, channel %s.",
            snapshot.installedVersion(), snapshot.channel().label());
        info("%s", snapshot.statusLine());
        info("Last checked: %s.", snapshot.lastCheckedText());
        if (snapshot.hasUpdate()) {
            info("Use (highlight).log update download(default) to fetch %s.", snapshot.latestVersion());
        }
    }

    private void updateCheck() {
        var module = updateModule();
        if (module == null) {
            warning("The updates module is not available.");
            return;
        }
        if (!module.isActive()) module.toggle();
        module.checkNow();
        info("Checking GitHub Releases for a newer build...");
    }

    private void updateDownload() {
        var snapshot = com.logvex.addon.update.mc.UpdateService.get().snapshot();
        if (!snapshot.hasUpdate()) {
            warning("No update is available to download. Run (highlight).log update check(default) first.");
            return;
        }
        com.logvex.addon.update.mc.UpdateService.get().download();
        info("Downloading %s. You will be told when it is ready.", snapshot.latestVersion());
    }

    private void updateChannel(String name) {
        var channel = com.logvex.addon.update.core.UpdateChannel.parse(name, null);
        if (channel == null) {
            warning("Unknown channel \"%s\". Use (highlight)stable(default) or (highlight)beta(default).", name);
            return;
        }
        var module = updateModule();
        if (module != null) {
            @SuppressWarnings("unchecked")
            var channelSetting = (com.logvex.addon.framework.settings.Setting<com.logvex.addon.update.core.UpdateChannel>)
                module.settings.get("channel");
            channelSetting.set(channel);
        }
        com.logvex.addon.update.mc.UpdateService.get().setChannel(channel);
        info("Update channel set to %s.", channel.label());
    }

    private void updateGui() {
        var module = updateModule();
        if (module == null) {
            warning("The updates module is not available.");
            return;
        }
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) mc.setScreen(new com.logvex.addon.update.gui.UpdateScreen(module));
    }

    // ------------------------------------------------------------------ web console

    private void openWebConsole() {
        var module = companionModule();
        if (module == null) {
            warning("The web console module is not available.");
            return;
        }
        if (!module.isActive()) {
            module.toggle();
        }
        if (!module.isRunning()) {
            warning("The web console could not start. Is port %d already in use?",
                module.settings.get("port") == null ? 6767 : module.settings.get("port").get());
            return;
        }
        String url = module.url();
        info("Web console listening on port %d.", module.boundPort());
        info("Open %s in a browser. The access token is under (highlight).log companion token(default).", url);
        try {
            net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
        } catch (RuntimeException e) {
            info("Open %s manually in a browser.", url);
        }
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
