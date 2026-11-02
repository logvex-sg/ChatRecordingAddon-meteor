package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.LogExporter;
import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.LogSegmentMeta;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.chatlog.modules.ChatMonitorModule;
import com.logvex.addon.chatlog.modules.KeywordAlertsModule;
import com.logvex.addon.chatlog.modules.PlayerActivityModule;
import com.logvex.addon.chatlog.modules.SessionStatsModule;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The Log Addon dashboard: a single Meteor-native window with live, search, players, keywords and
 * statistics tabs. Every control invokes the real module functionality and every value is read from
 * the shared runtime.
 */
public class LogDashboardScreen extends WindowScreen {
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private final ChatLoggerModule logger;

    private int tab;

    // Live tab state
    private String liveFilter = "";

    // Search tab state
    private String searchText = "";
    private String searchPlayer = "";
    private boolean newestFirst = true;
    private final Set<MessageType> searchTypes = EnumSet.allOf(MessageType.class);

    public LogDashboardScreen(ChatLoggerModule logger) {
        this(logger, 0);
    }

    public LogDashboardScreen(ChatLoggerModule logger, int tab) {
        super(GuiThemes.get(), "Log Addon");
        this.logger = logger;
        this.tab = tab;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        WTable tabs = add(theme.table()).expandX().widget();
        tabButton(tabs, "Live", 0);
        tabButton(tabs, "Search", 1);
        tabButton(tabs, "Players", 2);
        tabButton(tabs, "Keywords", 3);
        tabButton(tabs, "Stats", 4);

        add(theme.horizontalSeparator()).expandX();

        switch (tab) {
            case 0 -> renderLive();
            case 1 -> renderSearch();
            case 2 -> renderPlayers();
            case 3 -> renderKeywords();
            case 4 -> renderStats();
            default -> renderLive();
        }
    }

    private void tabButton(WTable table, String label, int index) {
        WButton button = table.add(theme.button(index == tab ? "[" + label + "]" : label)).widget();
        button.action = () -> {
            tab = index;
            taskAfterRender = this::render;
        };
    }

    // ------------------------------------------------------------------ live

    private void renderLive() {
        LogService service = LogService.get();

        var status = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(status, theme, "Recording:",
            (service.isRecording() ? "Yes" : "No") + "  (" + service.liveCount() + " live)");
        GuiKit.keyValue(status, theme, "Session:",
            service.session() == null ? "-" : service.session().id());
        GuiKit.keyValue(status, theme, "Server:", service.serverName());

        WTable controls = add(theme.table()).expandX().widget();
        if (logger != null) {
            WButton toggle = controls.add(theme.button(service.isRecording() ? "Stop recording" : "Start recording")).widget();
            toggle.action = () -> {
                if (service.isRecording()) logger.stopRecording();
                else logger.startRecording();
                taskAfterRender = this::render;
            };

            WButton flush = controls.add(theme.button("Flush to disk")).widget();
            flush.action = () -> {
                service.flush(null);
                logger.feedback(true, "Flushing buffered messages.");
            };

            WButton export = controls.add(theme.button("Export session")).widget();
            export.action = () -> {
                logger.exportSession();
                logger.feedback(true, "Export started.");
            };

            WButton sendNow = controls.add(theme.button("Send Now")).widget();
            sendNow.action = () -> logger.sendReport(true);

            WButton saved = controls.add(theme.button("Saved Reports")).widget();
            saved.action = () -> mc.setScreen(new StorageScreen(logger));

            WButton archive = controls.add(theme.button("Archive Settings")).widget();
            archive.action = () -> mc.setScreen(new StorageScreen(logger));
        }

        var filterBox = add(theme.textBox(liveFilter, "Filter live feed...")).expandX().widget();
        filterBox.action = () -> {
            liveFilter = filterBox.get();
            taskAfterRender = this::render;
        };

        add(theme.horizontalSeparator("Recent messages")).expandX();

        ChatMonitorModule monitor = Modules.get().get(ChatMonitorModule.class);
        List<ChatEntry> feed = monitor == null ? service.recent(20) : monitor.visibleFeed(20);
        if (!liveFilter.isBlank()) {
            String needle = liveFilter.toLowerCase(java.util.Locale.ROOT);
            feed = feed.stream().filter(entry -> entry.matches(needle)).toList();
        }

        if (feed.isEmpty()) {
            add(theme.label("No messages recorded yet.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Time")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Type")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Sender")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Message")).expandCellX().widget().color(GuiKit.MUTED);
        table.row();

        for (ChatEntry entry : feed) {
            table.add(theme.label(ReportFormatting.shortTime(entry.timestamp()))).widget().color(GuiKit.MUTED);
            table.add(theme.label(entry.type().toString())).widget().color(typeColor(entry));
            table.add(theme.label(entry.sender().isBlank() ? "-" : entry.sender())).widget();
            table.add(theme.label(GuiKit.truncate(entry.content(), 90))).expandCellX().widget();
            table.row();
        }
    }

    // ------------------------------------------------------------------ search

    private void renderSearch() {
        LogStorageService storage = logger == null ? null : logger.storage();
        if (storage == null) {
            add(theme.label("Storage is unavailable. Enable the Chat Logger module first.")).expandX();
            return;
        }

        var textBox = add(theme.textBox(searchText, "Search text...")).expandX().widget();
        textBox.action = () -> searchText = textBox.get();

        var playerBox = add(theme.textBox(searchPlayer, "Filter by player...")).expandX().widget();
        playerBox.action = () -> searchPlayer = playerBox.get();

        WTable typeRow = add(theme.table()).expandX().widget();
        for (MessageType type : MessageType.values()) {
            WButton toggle = typeRow.add(theme.button(searchTypes.contains(type) ? "[" + type + "]" : type.toString())).widget();
            toggle.action = () -> {
                if (searchTypes.contains(type)) searchTypes.remove(type);
                else searchTypes.add(type);
                taskAfterRender = this::render;
            };
        }

        WTable actions = add(theme.table()).expandX().widget();
        WButton order = actions.add(theme.button(newestFirst ? "Newest first" : "Oldest first")).widget();
        order.action = () -> {
            newestFirst = !newestFirst;
            taskAfterRender = this::render;
        };

        WButton search = actions.add(theme.button("Search")).widget();
        search.action = () -> runSearch(storage);

        WButton export = actions.add(theme.button("Export results")).widget();
        export.action = () -> {
            LogQuery query = buildQuery();
            String name = (logger == null ? "chatlog" : logger.exportFileName("chatlog-query"))
                + "." + (logger == null ? "txt" : logger.defaultExportFormat().extension());
            var destination = storage.root().resolve("exports").resolve(name);
            LogExporter.Format format = logger == null ? LogExporter.Format.TXT : logger.defaultExportFormat();
            storage.exportAsync(query, destination, format,
                count -> info("Exported " + count + " messages."),
                error -> info("Export failed: " + error));
        };

        add(theme.label("Use Search to load matching messages.")).expandX().widget().color(GuiKit.MUTED);
    }

    private void runSearch(LogStorageService storage) {
        LogQuery query = buildQuery();
        taskAfterRender = () -> mc.setScreen(new LogViewerScreen(storage, query, "Search results"));
    }

    private LogQuery buildQuery() {
        LogQuery.Builder builder = LogQuery.builder();
        if (!searchText.isBlank()) builder.text(searchText);
        if (!searchPlayer.isBlank()) builder.player(searchPlayer);
        if (searchTypes.size() != MessageType.values().length) builder.types(EnumSet.copyOf(searchTypes));
        return builder.build();
    }

    // ------------------------------------------------------------------ players

    private void renderPlayers() {
        PlayerActivityModule module = Modules.get().get(PlayerActivityModule.class);
        WButton open = add(theme.button("Open player activity overview")).expandX().widget();
        open.action = () -> {
            if (module == null) {
                info("The player activity module is not available.");
                return;
            }
            mc.setScreen(new PlayerOverviewScreen(module, logger == null ? null : logger.storage()));
        };

        add(theme.horizontalSeparator()).expandX();
        if (module == null) {
            add(theme.label("The player activity module is not available.")).expandX();
            return;
        }

        List<com.logvex.addon.chatlog.core.PlayerActivity> top = module.players("").stream().limit(10).toList();
        if (top.isEmpty()) {
            add(theme.label("No player activity recorded.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Player")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Messages")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Last seen")).widget().color(GuiKit.MUTED);
        table.row();
        for (var activity : top) {
            table.add(theme.label(activity.name())).expandCellX().widget();
            table.add(theme.label(Long.toString(activity.messageCount()))).widget();
            table.add(theme.label(ReportFormatting.time(activity.lastSeen()))).widget().color(GuiKit.MUTED);
            table.row();
        }
    }

    // ------------------------------------------------------------------ keywords

    private void renderKeywords() {
        KeywordAlertsModule module = Modules.get().get(KeywordAlertsModule.class);
        WButton open = add(theme.button("Manage keyword rules")).expandX().widget();
        open.action = () -> {
            if (module == null) {
                info("The keyword alerts module is not available.");
                return;
            }
            mc.setScreen(new KeywordManagerScreen(module));
        };

        WButton test = add(theme.button("Test webhook")).expandX().widget();
        test.action = () -> {
            if (module == null) {
                info("The keyword alerts module is not available.");
                return;
            }
            module.testWebhook(module);
            info("Webhook test requested.");
        };

        add(theme.horizontalSeparator()).expandX();
        if (module == null) {
            add(theme.label("The keyword alerts module is not available.")).expandX();
            return;
        }

        var rules = module.currentRules();
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Active rules:", Integer.toString(rules.size()));
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Webhook:",
            module.webhookUrl().isBlank() ? "not configured" : "configured");

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Pattern")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Sound")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Webhook")).widget().color(GuiKit.MUTED);
        table.row();
        for (var rule : rules) {
            table.add(theme.label(rule.pattern())).expandCellX().widget();
            table.add(theme.label(rule.sound() ? "yes" : "no")).widget();
            table.add(theme.label(rule.webhook() ? "yes" : "no")).widget();
            table.row();
        }
    }

    // ------------------------------------------------------------------ stats

    private void renderStats() {
        SessionStatsModule module = Modules.get().get(SessionStatsModule.class);
        StatsSnapshot snapshot = LogService.get().snapshot();

        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Total messages:", Long.toString(snapshot.totalMessages()));
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Rate:",
            String.format(java.util.Locale.ROOT, "%.1f/min", snapshot.messagesPerMinute()));
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Unique players:", Integer.toString(snapshot.uniquePlayers()));
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Joins / leaves:",
            snapshot.joinEvents() + " / " + snapshot.leaveEvents());
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Deaths:", Long.toString(snapshot.deathEvents()));
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Keyword alerts:", Long.toString(snapshot.keywordAlerts()));
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Duration:", ReportFormatting.duration(snapshot.durationMs()));

        add(theme.horizontalSeparator("By type")).expandX();
        WTable table = add(theme.table()).expandX().widget();
        for (MessageType type : MessageType.values()) {
            long count = snapshot.count(type);
            if (count == 0) continue;
            table.add(theme.label(type.toString())).expandCellX().widget();
            table.add(theme.label(Long.toString(count))).widget();
            table.row();
        }

        if (!snapshot.topPlayers().isEmpty()) {
            add(theme.horizontalSeparator("Top players")).expandX();
            WVerticalList list = add(theme.verticalList()).expandX().widget();
            for (String player : snapshot.topPlayers()) GuiKit.muted(list, theme, player);
        }

        add(theme.horizontalSeparator("Archive")).expandX();
        if (logger != null && logger.storage() != null) {
            LogStorageService storage = logger.storage();
            List<LogSegmentMeta> segments = storage.segments();
            GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Segments:", Integer.toString(segments.size()));
            GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Stored entries:",
                Long.toString(storage.store().totalEntries()));
            GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Compressed:",
                ReportFormatting.bytes(storage.store().totalCompressedBytes()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static meteordevelopment.meteorclient.utils.render.color.Color typeColor(ChatEntry entry) {
        return switch (entry.type()) {
            case PUBLIC_CHAT, PLAYER_MESSAGE -> GuiKit.ACCENT;
            case JOIN -> GuiKit.GOOD;
            case LEAVE -> GuiKit.WARN;
            case DEATH -> GuiKit.BAD;
            default -> GuiKit.MUTED;
        };
    }

    private void info(String message) {
        if (logger != null) logger.feedback(true, message);
    }
}
