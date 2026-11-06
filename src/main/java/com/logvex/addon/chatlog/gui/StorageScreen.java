package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.LogSegmentMeta;
import com.logvex.addon.chatlog.core.LogStore;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;

import java.util.List;

/** Archive management: statistics, retention, and per-segment maintenance. */
public class StorageScreen extends WindowScreen {
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private final ChatLoggerModule logger;
    private final LogStorageService storage;
    private int page;
    private String serverFilter = "";
    private boolean newestFirst = true;

    public StorageScreen(ChatLoggerModule logger) {
        super(GuiThemes.get(), "Log Archive");
        this.logger = logger;
        this.storage = logger == null ? null : logger.storage();
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        if (storage == null) {
            add(theme.label("Storage is unavailable. Enable the Chat Logger module first.")).expandX();
            return;
        }

        LogStore store = storage.store();
        long compressed = store.totalCompressedBytes();
        long uncompressed = store.totalUncompressedBytes();
        long saved = Math.max(0, uncompressed - compressed);

        add(theme.horizontalSeparator("Storage")).expandX();
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Location:", store.root().toString());
        var stats = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(stats, theme, "Segments:", Integer.toString(store.segmentCount()));
        GuiKit.keyValue(stats, theme, "Entries:", Long.toString(store.totalEntries()));
        GuiKit.keyValue(stats, theme, "Compressed size:", ReportFormatting.bytes(compressed));
        GuiKit.keyValue(stats, theme, "Uncompressed size:", ReportFormatting.bytes(uncompressed));
        GuiKit.keyValue(stats, theme, "Space saved:", ReportFormatting.bytes(saved));
        GuiKit.keyValue(stats, theme, "Oldest entry:", store.oldestTimestamp() == 0 ? "-" : ReportFormatting.time(store.oldestTimestamp()));
        GuiKit.keyValue(stats, theme, "Newest entry:", store.newestTimestamp() == 0 ? "-" : ReportFormatting.time(store.newestTimestamp()));

        add(theme.horizontalSeparator("Retention")).expandX();
        var retention = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(retention, theme, "Max age (days):", Integer.toString(logger.maxAgeDays()));
        GuiKit.keyValue(retention, theme, "Max size:", ReportFormatting.bytes(logger.maxBytes()));
        GuiKit.keyValue(retention, theme, "Max segments:", Integer.toString(logger.maxSegmentsSetting()));

        WTable actions = add(theme.table()).expandX().widget();
        WButton cleanup = actions.add(theme.button("Cleanup now")).widget();
        cleanup.action = () -> {
            logger.runCleanup();
            info("Cleanup requested.");
            taskAfterRender = this::render;
        };
        WButton rescan = actions.add(theme.button("Rebuild index")).widget();
        rescan.action = () -> {
            storage.rebuildIndexAsync(() -> taskAfterRender = this::render);
            info("Rebuilding the log index.");
        };
        WButton export = actions.add(theme.button("Export session")).widget();
        export.action = () -> {
            if (logger.service().session() == null) {
                info("No active session to export.");
                return;
            }
            logger.exportSession();
            info("Export started; see the chat log for the result.");
        };
        WButton importLog = actions.add(theme.button("Import log...")).widget();
        importLog.action = () -> mc.setScreen(new LogImportScreen(storage));
        WButton deleteAll = actions.add(theme.button("Delete all")).widget();
        deleteAll.action = () -> mc.setScreen(new ConfirmScreen("Delete all logs",
            "This permanently deletes every stored log segment. Continue?",
            () -> {
                storage.deleteAllAsync(() -> taskAfterRender = this::render);
                info("Deleting the entire archive.");
            }));

        add(theme.horizontalSeparator("Segments")).expandX();

        var filterRow = add(theme.table()).expandX().widget();
        var filterBox = filterRow.add(theme.textBox(serverFilter, "Filter by server...")).expandX().widget();
        filterBox.action = () -> {
            serverFilter = filterBox.get();
            taskAfterRender = this::render;
        };
        WButton sort = filterRow.add(theme.button(newestFirst ? "Newest first" : "Oldest first")).widget();
        sort.action = () -> {
            newestFirst = !newestFirst;
            taskAfterRender = this::render;
        };
        filterRow.row();

        renderSegments();
    }

    private void renderSegments() {
        List<LogSegmentMeta> segments = new java.util.ArrayList<>(storage.segments());
        String needle = serverFilter == null ? "" : serverFilter.strip().toLowerCase(java.util.Locale.ROOT);
        if (!needle.isEmpty()) {
            segments.removeIf(meta -> meta.server() == null
                || !meta.server().toLowerCase(java.util.Locale.ROOT).contains(needle));
        }
        segments.sort((a, b) -> newestFirst
            ? Long.compare(b.lastTimestamp(), a.lastTimestamp())
            : Long.compare(a.lastTimestamp(), b.lastTimestamp()));

        if (segments.isEmpty()) {
            add(theme.label("No stored segments.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        int pageSize = 100;
        int totalPages = Math.max(1, (segments.size() + pageSize - 1) / pageSize);
        page = Math.min(page, totalPages - 1);

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Started")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Server")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Entries")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Size")).widget().color(GuiKit.MUTED);
        table.add(theme.label("")).widget();
        table.row();

        int from = page * pageSize;
        int to = Math.min(segments.size(), from + pageSize);
        for (int i = from; i < to; i++) {
            LogSegmentMeta meta = segments.get(i);
            table.add(theme.label(ReportFormatting.time(meta.firstTimestamp()))).widget().color(GuiKit.MUTED);
            table.add(theme.label(meta.server().isBlank() ? "-" : meta.server())).expandCellX().widget();
            table.add(theme.label(Integer.toString(meta.count()))).widget();
            table.add(theme.label(ReportFormatting.bytes(meta.compressedSize()))).widget();
            WButton delete = table.add(theme.button("Delete")).widget();
            delete.action = () -> mc.setScreen(new ConfirmScreen("Delete segment",
                "Delete " + meta.count() + " stored messages from " + meta.server() + "?",
                () -> {
                    storage.deleteSegmentAsync(meta.fileName(), () -> taskAfterRender = this::render);
                    info("Deleting segment.");
                }));
            table.row();
        }

        if (totalPages > 1) {
            WTable controls = add(theme.table()).expandX().widget();
            WButton prev = controls.add(theme.button("Previous")).widget();
            prev.action = () -> {
                if (page > 0) {
                    page--;
                    taskAfterRender = this::render;
                }
            };
            WButton next = controls.add(theme.button("Next")).widget();
            next.action = () -> {
                if (page < totalPages - 1) {
                    page++;
                    taskAfterRender = this::render;
                }
            };
        }
    }

    private void info(String message) {
        if (logger != null) logger.feedback(true, message);
    }
}
