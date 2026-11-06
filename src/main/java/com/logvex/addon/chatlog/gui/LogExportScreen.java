package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.LogExporter;
import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Exports a chosen slice of the archive to a file.
 *
 * <p>Scope, time window, type filter, player and keyword are all applied through the same
 * {@link LogQuery} the search screen uses, so what the preview counts is exactly what is written.
 * The count comes from the storage worker; the write itself runs there too and never blocks the
 * client thread.
 */
public class LogExportScreen extends WindowScreen {
    private final LogStorageService storage;
    private final ChatLoggerModule logger;
    private final LogService service = LogService.get();

    private LogExporter.Format format = LogExporter.Format.TXT;
    private Scope scope = Scope.SESSION;
    private String player = "";
    private String text = "";
    private final Set<MessageType> types = EnumSet.allOf(MessageType.class);

    private int matchCount = -1;
    private boolean counting;

    private enum Scope {
        SESSION("Current session"),
        ALL("Entire archive"),
        TODAY("Today"),
        WEEK("Last 7 days");

        private final String label;

        Scope(String label) {
            this.label = label;
        }
    }

    public LogExportScreen(ChatLoggerModule logger) {
        super(GuiThemes.get(), "Export Logs");
        this.logger = logger;
        this.storage = logger == null ? null : logger.storage();
    }

    @Override
    public void initWidgets() {
        render();
    }

    private LogQuery buildQuery() {
        LogQuery.Builder builder = LogQuery.builder();
        long now = System.currentTimeMillis();
        switch (scope) {
            case SESSION -> {
                if (service.session() != null) builder.session(service.session().id());
            }
            case TODAY -> builder.from(startOfToday(now));
            case WEEK -> builder.from(now - 7L * 86_400_000L);
            case ALL -> {
            }
        }
        if (!types.isEmpty() && types.size() < MessageType.values().length) {
            builder.types(EnumSet.copyOf(types));
        }
        if (!player.isBlank()) builder.player(player);
        if (!text.isBlank()) builder.text(text);
        return builder.build();
    }

    private void render() {
        clear();

        if (storage == null) {
            add(theme.label("Storage is unavailable. Enable the Chat Logger module first.")).expandX();
            return;
        }

        add(theme.label("Scope")).expandX().widget().color(GuiKit.MUTED);
        WTable scopes = add(theme.table()).expandX().widget();
        for (Scope value : Scope.values()) {
            WButton button = scopes.add(theme.button(scope == value ? "> " + value.label : value.label)).widget();
            button.action = () -> {
                scope = value;
                refreshCount();
            };
        }
        scopes.row();

        add(theme.label("Format")).expandX().widget().color(GuiKit.MUTED);
        WTable formats = add(theme.table()).expandX().widget();
        for (LogExporter.Format value : LogExporter.Format.values()) {
            WButton button = formats.add(theme.button(format == value ? "> " + value.name() : value.name())).widget();
            button.action = () -> {
                format = value;
                render();
            };
        }
        formats.row();

        add(theme.horizontalSeparator("Filters")).expandX();

        WTable fields = add(theme.table()).expandX().widget();
        WTextBox playerBox = fields.add(theme.textBox(player, "Player (blank = all)")).expandCellX().widget();
        playerBox.action = () -> {
            player = playerBox.get();
            refreshCount();
        };
        WTextBox textBox = fields.add(theme.textBox(text, "Keyword (blank = all)")).expandCellX().widget();
        textBox.action = () -> {
            text = textBox.get();
            refreshCount();
        };
        fields.row();

        WTable typeRow = add(theme.table()).expandX().widget();
        for (MessageType type : MessageType.values()) {
            boolean on = types.contains(type);
            WButton toggle = typeRow.add(theme.button((on ? "+ " : "- ") + type.toString())).widget();
            toggle.action = () -> {
                if (!types.remove(type)) types.add(type);
                refreshCount();
            };
        }
        typeRow.row();

        add(theme.horizontalSeparator("Preview")).expandX();
        String count = counting ? "counting..." : (matchCount < 0 ? "unknown" : matchCount + " matching messages");
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Matches:", count);
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Destination:",
            storage.root().resolve("exports").toString());

        WButton export = add(theme.button("Export " + matchCount + " messages")).expandX().widget();
        export.action = () -> {
            if (logger == null) {
                info("The chat logger module is not available.");
                return;
            }
            boolean started = logger.exportQuery(buildQuery(), "chatlog-export", format);
            if (started) info("Export started; the result appears in chat when it finishes.");
        };
        WButton recount = add(theme.button("Recount")).expandX().widget();
        recount.action = this::refreshCount;

        if (matchCount == 0) {
            add(theme.label("Nothing matches the current filters.")).expandX().widget().color(GuiKit.WARN);
        }

        if (matchCount < 0 && !counting) refreshCount();
    }

    private void refreshCount() {
        counting = true;
        LogQuery query = buildQuery();
        storage.searchListAsync(query, 100_000, this::onCounted, count -> {});
        taskAfterRender = this::render;
    }

    private void onCounted(List<ChatEntry> loaded) {
        counting = false;
        matchCount = loaded == null ? 0 : loaded.size();
        taskAfterRender = this::render;
    }

    private static long startOfToday(long now) {
        return java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();
    }

    private void info(String message) {
        com.logvex.addon.framework.utils.player.ChatUtils.info("%s", message);
    }

    @Override
    public boolean toClipboard() {
        return false;
    }

    @Override
    public boolean fromClipboard() {
        return false;
    }
}
