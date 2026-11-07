package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.chatlog.modules.SessionHistoryModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.player.ChatUtils;

import java.util.List;

/**
 * A browser for past recording sessions: server, duration and observed counts.
 *
 * <p>Everything here was measured during the session; a value that was never observed is shown as
 * a dash rather than a zero.
 */
public class SessionHistoryScreen extends WindowScreen {
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();

    private final SessionHistoryModule module;
    private String filter = "";

    public SessionHistoryScreen(SessionHistoryModule module) {
        super(GuiThemes.get(), "Session History");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        if (module == null) {
            add(theme.label("The session history module is not available.")).expandX();
            return;
        }

        var search = add(theme.textBox(filter, "Search by server or date...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            taskAfterRender = this::render;
        };

        WTable controls = add(theme.table()).expandX().widget();
        WButton clear = controls.add(theme.button("Clear History")).widget();
        clear.action = () -> {
            module.clear();
            taskAfterRender = this::render;
        };

        var summary = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(summary, theme, "Sessions kept:", Integer.toString(module.size()));
        GuiKit.keyValue(summary, theme, "Messages total:", Messages.number(module.totalMessages()));
        GuiKit.keyValue(summary, theme, "Time total:", Messages.duration(module.totalDurationMs()));

        List<SessionRecord> sessions = module.sessions(filter);
        add(theme.horizontalSeparator("Sessions (" + sessions.size() + ")")).expandX();

        if (sessions.isEmpty()) {
            add(theme.label("No sessions recorded yet.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Started")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Server")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Duration")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Messages")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Players")).widget().color(GuiKit.MUTED);
        table.add(theme.label("J/L/D")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Alerts")).widget().color(GuiKit.MUTED);
        table.add(theme.label("")).widget();
        table.row();

        long now = System.currentTimeMillis();
        for (SessionRecord record : sessions) {
            table.add(theme.label(Messages.dateTime(record.startedAt()))).widget();
            table.add(theme.label(record.server())).expandCellX().widget();
            table.add(theme.label(Messages.duration(record.durationMs(now)))).widget();
            table.add(theme.label(Messages.number(record.messages()))).widget();
            table.add(theme.label(Long.toString(record.uniquePlayers()))).widget();
            table.add(theme.label(record.joins() + "/" + record.leaves() + "/" + record.deaths())).widget();
            table.add(theme.label(Long.toString(record.keywordAlerts()))).widget();

            WButton view = table.add(theme.button("View Log")).widget();
            view.action = () -> {
                var logger = com.logvex.addon.framework.systems.modules.Modules.get()
                    .get(com.logvex.addon.chatlog.modules.ChatLoggerModule.class);
                if (logger != null && logger.storage() != null) {
                    mc.setScreen(new LogViewerScreen(logger.storage(),
                        com.logvex.addon.chatlog.core.LogQuery.builder().session(record.id()).build(),
                        "Session " + record.id()));
                } else {
                    info("Storage is unavailable.");
                }
            };
            table.row();
        }
    }

    private void info(String message) {
        ChatUtils.info("%s", message);
    }
}
