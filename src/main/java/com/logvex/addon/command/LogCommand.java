package com.logvex.addon.command;

import com.logvex.addon.LogClient;
import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.common.Rows;
import com.logvex.addon.notify.NotificationModule;
import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.schematicdropper.SchematicDropperModule;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import com.logvex.addon.assistant.core.ReplyFormat.Field;

import java.util.ArrayList;
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

        builder.then(literal("help")
            .executes(context -> {
                help();
                return SINGLE_SUCCESS;
            })
            .then(argument("group", StringArgumentType.word()).executes(context -> {
                help(StringArgumentType.getString(context, "group"));
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("status").executes(context -> {
            status();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("gui").executes(context -> {
            openGui();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("modules")
            .executes(context -> {
                modules();
                return SINGLE_SUCCESS;
            })
            .then(literal("check").executes(context -> {
                modulesCheck();
                return SINGLE_SUCCESS;
            }))
        );

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
                info("Usage: (highlight)" + pfx() + "log import <file>(default).");
                return SINGLE_SUCCESS;
            })
            .then(argument("path", StringArgumentType.greedyString()).executes(context -> {
                importMaterials(StringArgumentType.getString(context, "path"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("importlog")
            .executes(context -> {
                info("Usage: (highlight)" + pfx() + "log importlog <file>(default).");
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

        builder.then(literal("overview").executes(context -> {
            overview();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("summary")
            .executes(context -> {
                sendSummary("");
                return SINGLE_SUCCESS;
            })
            .then(argument("note", StringArgumentType.greedyString()).executes(context -> {
                sendSummary(StringArgumentType.getString(context, "note"));
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("search")
            .executes(context -> {
                search("");
                return SINGLE_SUCCESS;
            })
            .then(argument("terms", StringArgumentType.greedyString()).executes(context -> {
                search(StringArgumentType.getString(context, "terms"));
                return SINGLE_SUCCESS;
            }))
        );

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

        builder.then(literal("webhook")
            .executes(context -> {
                webhookStatus();
                return SINGLE_SUCCESS;
            })
            .then(literal("status").executes(context -> {
                webhookStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("test").executes(context -> {
                webhookTest();
                return SINGLE_SUCCESS;
            }))
            .then(literal("preview").executes(context -> {
                webhookPreview();
                return SINGLE_SUCCESS;
            }))
            .then(literal("digest").executes(context -> {
                webhookDigest();
                return SINGLE_SUCCESS;
            }))
            .then(literal("set").then(argument("url", StringArgumentType.greedyString()).executes(context -> {
                webhookSet(StringArgumentType.getString(context, "url"));
                return SINGLE_SUCCESS;
            })))
            .then(literal("accent").then(argument("name", StringArgumentType.word()).executes(context -> {
                webhookAccent(StringArgumentType.getString(context, "name"));
                return SINGLE_SUCCESS;
            })))
            .then(literal("leaderboard").executes(context -> {
                webhookLeaderboard();
                return SINGLE_SUCCESS;
            }))
            .then(literal("profile").then(argument("player", StringArgumentType.greedyString()).executes(context -> {
                webhookProfile(StringArgumentType.getString(context, "player"));
                return SINGLE_SUCCESS;
            })))
            .then(literal("sendstatus").executes(context -> {
                webhookStatusEmbed();
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

    /** How many block lines one command answer may occupy before the rest is summarised. */
    /** The addon's configured command prefix, so this list matches what the user actually types. */
    private static String pfx() {
        return com.logvex.addon.framework.systems.config.ClientConfig.get().prefix();
    }

    /** Prints a summary block; see {@link CommandOutput} for the layout rules. */
    private void embed(String title, java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields) {
        CommandOutput.send(this, title, fields);
    }

    private void embed(String title, java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields,
                       String footer) {
        CommandOutput.send(this, title, fields, footer);
    }

    /** A block of records, allowed the larger line budget so the list is not cut short. */
    private void embedLong(String title,
                           java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields) {
        CommandOutput.sendLong(this, title, fields, "");
    }

    private void embedLong(String title,
                           java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields,
                           String footer) {
        CommandOutput.sendLong(this, title, fields, footer);
    }

    private void help() {
        java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (com.logvex.addon.common.CommandCatalog.GroupSummary row
            : com.logvex.addon.common.CommandCatalog.groupSummary()) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                row.group().title(),
                row.count() + (row.count() == 1 ? " command - " : " commands - ")
                    + pfx() + "log help " + row.group().key()));
        }
        CommandOutput.sendList(this, "Log Client commands", fields,
            pfx() + "prefix copies every command to the clipboard");
    }

    /** One group of commands, in detail. */
    private void help(String groupName) {
        com.logvex.addon.common.CommandCatalog.Group group =
            com.logvex.addon.common.CommandCatalog.Group.parse(groupName);
        if (group == null) {
            warning("No group called '%s'. Groups: %s", groupName,
                java.util.Arrays.stream(com.logvex.addon.common.CommandCatalog.Group.values())
                    .map(com.logvex.addon.common.CommandCatalog.Group::key)
                    .collect(java.util.stream.Collectors.joining(", ")));
            return;
        }
        java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (com.logvex.addon.common.CommandCatalog.Entry entry
            : com.logvex.addon.common.CommandCatalog.inGroup(group)) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                com.logvex.addon.common.CommandCatalog.prefixed(pfx(), entry), entry.description()));
        }
        CommandOutput.sendList(this, group.title() + " commands", fields,
            pfx() + "prefix to copy every command");
    }

    private void status() {
        java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();

        ChatLoggerModule logger = LogCommands.logger();
        if (logger != null) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Recording",
                logger.isRecording() ? "on" : "off"));
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Storage",
                logger.storageSummary()));
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Logger module",
                logger.isActive() ? "on" : "off"));
        } else {
            warning("Chat logger module not found.");
        }

        BotModule bot = LogCommands.bot();
        if (bot != null) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Automation",
                bot.statusLine()));
        }

        NotificationModule notifications = LogCommands.notifications();
        if (notifications != null) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Notifications",
                Messages.number(notifications.center().size()) + " stored, "
                    + Messages.number(notifications.center().accepted()) + " accepted, "
                    + Messages.number(notifications.center().suppressed()) + " suppressed"));
        }

        embed("Log Client status", fields,
            "Log Client " + com.logvex.addon.framework.LogClientContext.VERSION);
    }

    private void modules() {
        List<Module> modules = LogCommands.addonModules();
        if (modules.isEmpty()) {
            warning("No Log Client modules are registered.");
            return;
        }
        List<Field> rows = new ArrayList<>(modules.size());
        for (Module module : modules) {
            rows.add(new Field(module.isActive() ? "[on] " + module.title : "[off] " + module.title,
                Messages.truncate(module.description, 70)));
        }
        CommandOutput.sendList(this, "Log Client modules (" + modules.size() + ")", rows,
            "enabled " + modules.stream().filter(Module::isActive).count() + " of " + modules.size());
    }

    /**
     * Reports module names that more than one module claims.
     *
     * <p>A clash is silent at runtime — Meteor simply drops the earlier module — and only shows up
     * later as a crash in whatever looked that module up. This command makes it visible on demand,
     * which matters after adding or renaming a module or installing another addon.
     */
    private void modulesCheck() {
        java.util.Collection<Module> all = Modules.get().getAll();
        List<String> names = new ArrayList<>(all.size());
        for (Module module : all) names.add(module.name);

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Registered", Messages.number(all.size()) + " modules"));
        fields.add(new Field("Ours", Messages.number(LogCommands.addonModules().size())
            + " in the " + LogClient.CATEGORY.name + " category"));
        for (com.logvex.addon.framework.ModuleAudit.Clash clash
            : com.logvex.addon.framework.ModuleAudit.clashes(names)) {
            fields.add(new Field(clash.name(), "claimed " + clash.count() + " times"));
        }
        if (fields.size() == 2) {
            fields.add(new Field("Clashes", "none"));
        }
        embed("Module name check", fields,
            "a clash deletes the earlier module");
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
        java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Messages",
            Messages.number(snapshot.totalMessages()) + " in "
                + ReportFormatting.duration(snapshot.durationMs())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Rate",
            Messages.decimal(snapshot.messagesPerMinute(), 1) + " / min"));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Players",
            Messages.number(snapshot.uniquePlayers()) + " seen"));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Joins / leaves",
            Messages.number(snapshot.joinEvents()) + " / " + Messages.number(snapshot.leaveEvents())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Deaths",
            Messages.number(snapshot.deathEvents())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Keyword alerts",
            Messages.number(snapshot.keywordAlerts())));
        embed("Session statistics", fields,
            snapshot.topPlayers().isEmpty() ? "" : "Most active: " + String.join(", ", snapshot.topPlayers()));
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
        List<Field> rows = new ArrayList<>(recent.size());
        for (Notification notification : recent) {
            rows.add(new Field(notification.priority().name().toLowerCase(Locale.ROOT),
                Messages.truncate(notification.line(), 80)));
        }
        CommandOutput.sendList(this, "Recent notifications (" + recent.size() + ")", rows, "");
    }

    private void alerts(int count) {
        var log = com.logvex.addon.chatlog.mc.LogService.get().alerts();
        List<com.logvex.addon.chatlog.core.AlertLog.Alert> recent = log.recent("", count);
        if (recent.isEmpty()) {
            info("No alerts recorded yet.");
            return;
        }
        List<Field> rows = new ArrayList<>(recent.size());
        for (var alert : recent) {
            rows.add(new Field(alert.kind().id() + " " + alert.title(),
                Messages.truncate(alert.detail(), 70)));
        }
        CommandOutput.sendList(this, "Recent alerts (" + recent.size() + ")", rows,
            "totals: " + log.count(com.logvex.addon.chatlog.core.AlertLog.Kind.KEYWORD) + " keyword, "
                + log.count(com.logvex.addon.chatlog.core.AlertLog.Kind.WEBHOOK) + " webhook, "
                + log.count(com.logvex.addon.chatlog.core.AlertLog.Kind.ERROR) + " error");
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
        List<Field> rows = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            var session = list.get(i);
            String duration = session.hasMeasuredTime()
                ? ReportFormatting.duration(session.onlineMs(now))
                : "unknown";
            rows.add(new Field(session.name() + (session.online() ? " (online)" : ""),
                duration + " across " + Messages.plural(session.sessions(), "session", "sessions")));
        }
        String footer = list.size() > shown ? "and " + (list.size() - shown) + " more" : "";
        CommandOutput.sendLong(this, Messages.plural(list.size(), "player session", "player sessions")
            + (filter.isBlank() ? "" : " matching '" + filter + "'"), rows, footer);
    }

    private void tasks() {
        BotModule bot = LogCommands.bot();
        if (bot == null) {
            error("The bot module is not available.");
            return;
        }
        var queue = bot.controller().queue();
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Automation", bot.statusLine()));
        if (queue.isEmpty()) {
            fields.add(new Field("Queue", "empty"));
        } else {
            Rows.Capped<BotTask> capped = Rows.cap(queue.tasks(), 20);
            for (BotTask task : capped.shown()) {
                fields.add(new Field("#" + task.id(),
                    task.type() + " "
                        + (task.label().isBlank() ? task.itemId() : task.label())
                        + " (" + task.progress() + "/" + task.required() + ") " + task.state()));
            }
            if (capped.truncated()) fields.add(new Field("Queue", Rows.remainderNote(capped)));
        }
        var history = bot.controller().history();
        fields.add(new Field("History", Messages.number(history.size()) + " total, "
            + Messages.number(history.successCount()) + " ok, "
            + Messages.number(history.failureCount()) + " failed"));
        embedLong("Automation tasks", fields);
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
            info("No waypoints recorded. Stand somewhere and use (highlight)" + pfx() + "log waypoints add <name>(default).");
            return;
        }
        List<Field> rows = new ArrayList<>(waypoints.size());
        for (BotMemory.Waypoint waypoint : waypoints) {
            rows.add(new Field(waypoint.name(), waypoint.describe()));
        }
        CommandOutput.sendLong(this, Messages.plural(waypoints.size(), "waypoint", "waypoints"), rows,
            "");
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
            info("No schematic loaded. Use (highlight)" + pfx() + "log schematic load <path>(default).");
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
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Name", profile.label()));
        fields.add(new Field("Places", Messages.number(profile.locations().size())));
        fields.add(new Field("Biomes", Messages.number(profile.biomes().size())));
        fields.add(new Field("Resources", Messages.number(profile.resources().size())));
        fields.add(new Field("Chunks", Messages.number(profile.chunks().size())));
        var recent = profile.discoveries().recent(5);
        if (recent.isEmpty()) {
            fields.add(new Field("Discoveries", "none yet"));
        } else {
            for (var event : recent) {
                fields.add(new Field(String.valueOf(event.kind()),
                    event.label() + " at " + com.logvex.addon.world.core.WorldFormat.coords(
                        event.x(), event.y(), event.z())));
            }
        }
        embedLong("World", fields);
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
        var capped = Rows.cap(places, 20);
        List<Field> rows = new ArrayList<>(capped.shown().size());
        for (var point : capped.shown()) {
            rows.add(new Field(point.name().isBlank() ? "(unnamed)" : point.name(),
                point.category() + " "
                    + com.logvex.addon.world.core.WorldFormat.coords(point.x(), point.y(), point.z())));
        }
        CommandOutput.sendLong(this, Messages.plural(places.size(), "place", "places"), rows,
            capped.truncated() ? "and " + capped.remaining() + " more" : "");
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
        List<Field> rows = new ArrayList<>(points.size());
        for (var point : points) {
            double dx = point.x() - mc.player.getX();
            double dy = point.y() - mc.player.getY();
            double dz = point.z() - mc.player.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            rows.add(new Field(point.name().isBlank() ? point.category().toString() : point.name(),
                com.logvex.addon.world.core.WorldFormat.distance(distance)));
        }
        CommandOutput.sendLong(this, "Nearest known places", rows, "");
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

    /**
     * Prints one card summarising every subsystem at once.
     *
     * <p>The figures each had a home already — session statistics, the player index, the alert log,
     * the world database, the automation status — but no command showed them together, so "what has
     * happened this session?" meant running five commands and remembering the answers. This card is
     * built from the same readers those commands use, so it cannot drift from them.
     *
     * <p>Nothing is sent anywhere: unlike {@code log summary}, which pushes a report to a Discord
     * webhook, this prints in chat and needs no configuration.
     */
    private void overview() {
        ChatLoggerModule logger = LogCommands.logger();
        if (logger == null) {
            error("The chat logger module is not available.");
            return;
        }

        StatsSnapshot snapshot = logger.service().snapshot();
        String session = snapshot.durationMs() > 0
            ? ReportFormatting.duration(snapshot.durationMs()) + ", "
                + Messages.plural(snapshot.totalMessages(), "message")
            : "";
        String rate = snapshot.messagesPerMinute() > 0
            ? Messages.decimal(snapshot.messagesPerMinute(), 1) + " messages / min"
            : "";

        var sessions = com.logvex.addon.chatlog.mc.LogService.get().sessions().search("");
        int online = 0;
        for (var player : sessions) if (player.online()) online++;
        String onlineText = online > 0 ? online + " online now" : "";
        String seenText = snapshot.uniquePlayers() > 0
            ? Messages.plural(snapshot.uniquePlayers(), "player") + " seen"
            : "";

        var alerts = com.logvex.addon.chatlog.mc.LogService.get().alerts();
        String keyword = alerts.count(com.logvex.addon.chatlog.core.AlertLog.Kind.KEYWORD) > 0
            ? alerts.count(com.logvex.addon.chatlog.core.AlertLog.Kind.KEYWORD) + " keyword"
            : "";
        String other = com.logvex.addon.chatlog.core.SummaryCard.join(", ",
            alerts.count(com.logvex.addon.chatlog.core.AlertLog.Kind.WEBHOOK) > 0
                ? alerts.count(com.logvex.addon.chatlog.core.AlertLog.Kind.WEBHOOK) + " webhook" : "",
            alerts.count(com.logvex.addon.chatlog.core.AlertLog.Kind.ERROR) > 0
                ? alerts.count(com.logvex.addon.chatlog.core.AlertLog.Kind.ERROR) + " error" : "");

        String places = "";
        var world = LogCommands.world();
        if (world != null) {
            int known = world.service().intelligence().search("", null, null).size();
            if (known > 0) places = Messages.plural(known, "place") + " recorded";
        }

        BotModule bot = LogCommands.bot();
        String automation = bot != null ? bot.statusLine() : "";
        var companion = LogCommands.modules()
            .get(com.logvex.addon.companion.mc.CompanionModule.class);
        String companionText = companion == null ? ""
            : companion.isRunning() ? "listening on port " + companion.boundPort() : "off";

        var inputs = new com.logvex.addon.chatlog.core.SummaryCard.Inputs(
            session, rate, logger.storageSummary(), onlineText, seenText,
            keyword, other, places, automation, companionText);
        var sections = com.logvex.addon.chatlog.core.SummaryCard.sections(inputs);
        if (sections.isEmpty()) {
            info("Nothing recorded yet. Join a world and the card will fill in.");
            return;
        }

        List<Field> fields = new ArrayList<>();
        for (var pair : com.logvex.addon.chatlog.core.SummaryCard.fields(sections)) {
            fields.add(new Field(pair.label(), pair.value()));
        }
        embed("Log Client overview", fields,
            com.logvex.addon.framework.LogClientContext.VERSION);
    }

    /**
     * Sends the chat summary to the configured webhook as a report card.
     *
     * <p>Separate from {@code digest}, which prints locally and only forwards when the module is
     * set to. This one always goes to the webhook and records who asked, because its purpose is to
     * hand a summary to somebody else.
     */
    private void sendSummary(String note) {
        com.logvex.addon.chatlog.modules.WebhookRelayModule relay = LogCommands.relay();
        if (relay == null) {
            error("The webhook relay module is not available.");
            return;
        }
        if (!relay.configured()) {
            warning("Set a webhook URL on the webhook-relay module first.");
            return;
        }
        String who = mc.player == null ? "" : mc.player.getGameProfile().getName();
        relay.sendSummary(who, note);
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

    /**
     * Searches the recorded archive and prints the newest matches.
     *
     * <p>The store already knows how to query; what was missing was any way to reach it from chat,
     * which left a user staring at an archive they could only open in a text editor. Matches are
     * printed oldest-last so the most recent hit is nearest the input box.
     */
    private void search(String terms) {
        ChatLoggerModule logger = LogCommands.logger();
        if (logger == null || logger.storage() == null) {
            error("Storage is unavailable. Enable the Chat Logger module first.");
            return;
        }
        SearchArgs.Parsed parsed = SearchArgs.parse(terms);
        java.util.List<com.logvex.addon.chatlog.core.ChatEntry> matches;
        try {
            matches = logger.storage().store().query(parsed.query(), parsed.limit());
        } catch (java.io.IOException e) {
            error("Could not read the archive: %s", e.getMessage() == null ? "unknown error" : e.getMessage());
            return;
        }
        if (matches.isEmpty()) {
            info(terms.isBlank() ? "The archive is empty." : "No messages match that search.");
            return;
        }

        // The store hands back newest first; chat grows upward, so printing in that order would put
        // the newest match furthest from the input box. Reverse so the latest hit lands last.
        java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (int i = matches.size() - 1; i >= 0; i--) {
            var entry = matches.get(i);
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                com.logvex.addon.common.Messages.clock(entry.timestamp()),
                com.logvex.addon.common.Messages.player(entry.sender()) + ": "
                    + com.logvex.addon.common.Messages.truncate(entry.content(), 60)));
        }
        String footer = matches.size() >= parsed.limit()
            ? "Showing the newest " + parsed.limit() + " - raise it with limit:<n>"
            : com.logvex.addon.common.Messages.plural(matches.size(), "match", "matches");
        embed(terms.isBlank() ? "Recent messages" : "Search: " + terms, fields, footer);
    }

    private void timeline(String filter) {
        com.logvex.addon.chatlog.core.SessionTimeline timeline =
            com.logvex.addon.chatlog.mc.SessionTimelineService.get().timeline();
        var events = filter.isBlank() ? timeline.recent(15) : timeline.search(filter);
        if (events.isEmpty()) {
            info(filter.isBlank() ? "No session milestones yet." : "No milestones match that filter.");
            return;
        }
        int shown = Math.min(events.size(), 15);
        List<Field> rows = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            var event = events.get(i);
            rows.add(new Field(com.logvex.addon.common.Messages.clock(event.timestamp()), event.line()));
        }
        CommandOutput.sendLong(this, Messages.plural(events.size(), "session milestone", "session milestones"),
            rows, events.size() > shown ? "and " + (events.size() - shown) + " more" : "");
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
        int shown = Math.min(notes.size(), 15);
        List<Field> rows = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            var note = notes.get(i);
            rows.add(new Field(note.player(), com.logvex.addon.common.Messages.truncate(note.text(), 80)));
        }
        CommandOutput.sendLong(this, Messages.plural(notes.size(), "player note", "player notes"), rows,
            notes.size() > shown ? "and " + (notes.size() - shown) + " more" : "");
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
        int shown = Math.min(list.size(), 20);
        long now = System.currentTimeMillis();
        List<Field> rows = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            var identity = list.get(i);
            String previous = identity.previousName();
            rows.add(new Field(identity.currentName() + (previous.isEmpty() ? "" : " (was " + previous + ")"),
                Messages.plural(identity.nameCount(), "name", "names") + ", last seen "
                    + Messages.ago(identity.lastSeen(), now)));
        }
        CommandOutput.sendLong(this, Messages.plural(list.size(), "observed account", "observed accounts")
            + (filter.isBlank() ? "" : " matching '" + filter + "'"), rows,
            list.size() > shown ? "and " + (list.size() - shown) + " more" : "");
    }

    private void renames() {
        var renames = com.logvex.addon.chatlog.mc.LogService.get().identities().renames();
        if (renames.isEmpty()) {
            info("No player renames observed yet.");
            return;
        }
        long now = System.currentTimeMillis();
        var capped = Rows.cap(renames, 20);
        List<Field> fields = new ArrayList<>();
        for (var rename : capped.shown()) {
            fields.add(new Field(rename.from(), rename.to() + " (" + Messages.ago(rename.atMs(), now) + ")"));
        }
        String footer = capped.truncated()
            ? Rows.remainderNote(capped)
            : Messages.plural(renames.size(), "rename") + " observed";
        embedLong("Player renames", fields, footer);
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
            info("The watchlist is empty. Use (highlight)" + pfx() + "log identities watch <player>(default).");
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
        var capped = Rows.cap(history.search(""), 15);
        long now = System.currentTimeMillis();
        List<Field> fields = new ArrayList<>();
        for (var record : capped.shown()) {
            fields.add(new Field(Messages.dateTime(record.startedAt()),
                record.server() + " - " + Messages.duration(record.durationMs(now))
                    + ", " + Messages.plural(record.messages(), "message")
                    + ", " + Messages.plural(record.uniquePlayers(), "player")));
        }
        String footer = Messages.plural(history.size(), "session") + " kept, "
            + Messages.plural(history.totalMessages(), "message");
        if (capped.truncated()) footer = Rows.remainderNote(capped) + ", " + footer;
        embedLong("Past sessions", fields, footer);
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
            info("The companion is off. Use " + pfx() + "log companion start to turn it on.");
            return;
        }
        if (!module.isRunning()) {
            warning("The companion is on but not listening. Use " + pfx() + "log companion start to retry.");
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
            info("Use (highlight)" + pfx() + "log update download(default) to fetch %s.", snapshot.latestVersion());
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
            warning("No update is available to download. Run (highlight)" + pfx() + "log update check(default) first.");
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
            var channelSetting = (meteordevelopment.meteorclient.settings.Setting<com.logvex.addon.update.core.UpdateChannel>)
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
        info("Open %s in a browser. The access token is under (highlight)" + pfx() + "log companion token(default).", url);
        try {
            net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
        } catch (RuntimeException e) {
            info("Open %s manually in a browser.", url);
        }
    }

    // ------------------------------------------------------------------ webhook

    private com.logvex.addon.chatlog.modules.WebhookRelayModule relayModule() {
        return Modules.get().get(com.logvex.addon.chatlog.modules.WebhookRelayModule.class);
    }

    private void webhookStatus() {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        if (!relay.configured()) {
            info("No webhook configured. Use (highlight)" + pfx() + "log webhook set <url>(default).");
            return;
        }
        info("Webhook relay: %s, accent %s, %s",
            relay.isActive() ? "on" : "off",
            com.logvex.addon.chatlog.core.EmbedAccent.AUTO.id().equals(relay.style().accent())
                ? "auto" : relay.style().accent(),
            com.logvex.addon.chatlog.core.WebhookDeliveryLog.hostOf(relay.url()));
        var monitor = Modules.get().get(com.logvex.addon.chatlog.modules.WebhookMonitorModule.class);
        if (monitor != null) info("Delivery: %s", monitor.statusLine());
    }

    private void webhookTest() {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        relay.test();
    }

    private void webhookPreview() {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null) {
            mc.setScreen(new com.logvex.addon.chatlog.gui.EmbedPreviewScreen(relay));
        }
    }

    private void webhookDigest() {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        if (!relay.configured()) {
            warning("Set a webhook URL first.");
            return;
        }
        var digest = LogCommands.modules().get(com.logvex.addon.chatlog.modules.ChatDigestModule.class);
        var summary = digest != null ? digest.summary()
            : com.logvex.addon.chatlog.core.ChatDigest.summarise(
                com.logvex.addon.chatlog.mc.LogService.get().recent(200), 5);
        if (summary.isEmpty()) {
            warning("No messages to summarise.");
            return;
        }
        com.logvex.addon.chatlog.mc.LogService.get().notifyDigest(relay.url(), relay.username(), summary,
            (success, message) -> {
                if (success) info("Digest embed sent.");
                else warning("Digest send failed: %s", message);
            });
    }

    private void webhookSet(String url) {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        var setting = relay.settings.get("webhook-url");
        if (setting == null) {
            warning("The webhook URL setting is missing.");
            return;
        }
        @SuppressWarnings("unchecked")
        var typed = (meteordevelopment.meteorclient.settings.Setting<String>) setting;
        typed.set(url == null ? "" : url.trim());
        if (url == null || url.isBlank()) info("Webhook URL cleared.");
        else info("Webhook URL set (%s).", com.logvex.addon.chatlog.core.WebhookDeliveryLog.hostOf(url));
    }

    private void webhookLeaderboard() {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        relay.sendLeaderboard();
    }

    private void webhookProfile(String player) {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        relay.sendProfile(player == null ? "" : player.trim());
    }

    private void webhookStatusEmbed() {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        relay.sendStatus();
    }

    private void webhookAccent(String name) {
        var relay = relayModule();
        if (relay == null) {
            warning("The webhook-relay module is not available.");
            return;
        }
        var setting = relay.settings.get("accent");
        if (setting == null) {
            warning("The accent setting is missing.");
            return;
        }
        com.logvex.addon.chatlog.core.EmbedAccent accent =
            com.logvex.addon.chatlog.core.EmbedAccent.AUTO;
        for (var value : com.logvex.addon.chatlog.core.EmbedAccent.values()) {
            if (value.name().equalsIgnoreCase(name)) {
                accent = value;
                break;
            }
        }
        @SuppressWarnings("unchecked")
        var typed = (meteordevelopment.meteorclient.settings.Setting<com.logvex.addon.chatlog.core.EmbedAccent>) setting;
        typed.set(accent);
        info("Embed accent set to %s.", accent.id());
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
