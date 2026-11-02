package com.logvex.addon.chatrelay.gui;

import com.logvex.addon.chatrelay.core.ReportMetadata;
import com.logvex.addon.chatrelay.mc.ArchiveService;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Archive browser. Only {@link ReportMetadata} is read here; message bodies are decompressed lazily
 * by {@link ReportViewerScreen} when a report is opened.
 */
public class ReportBrowserScreen extends WindowScreen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color GREY = new Color(170, 170, 170);

    private final ArchiveService archive;

    private WTextBox search;
    private WButton sortButton;
    private WTable table;

    private String filter = "";
    private Sort sort = Sort.NEWEST;

    public ReportBrowserScreen(ArchiveService archive) {
        this(GuiThemes.get(), archive);
    }

    public ReportBrowserScreen(GuiTheme theme, ArchiveService archive) {
        super(theme, "Saved Chat Reports");
        this.archive = archive;
    }

    @Override
    public void initWidgets() {
        search = add(theme.textBox("", "Search by server or report id...")).minWidth(400).expandX().widget();
        search.action = () -> {
            filter = search.get().trim().toLowerCase(Locale.ROOT);
            refresh();
        };

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();

        sortButton = controls.add(theme.button("Sort: " + sort)).widget();
        sortButton.action = () -> {
            sort = sort.next();
            sortButton.set("Sort: " + sort);
            refresh();
        };

        WButton refreshButton = controls.add(theme.button("Refresh")).widget();
        refreshButton.action = this::refresh;

        WButton deleteAll = controls.add(theme.button("Delete All")).widget();
        deleteAll.action = () -> mc.setScreen(new ConfirmScreen(theme, "Delete all reports",
            "Delete all " + archive.count() + " archived reports? This cannot be undone.",
            () -> archive.deleteAllAsync(this::refresh)));

        add(theme.horizontalSeparator()).expandX();
        table = add(theme.table()).expandX().widget();
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 40 == 0) refresh();
    }

    private void refresh() {
        if (table == null) return;
        table.clear();

        long compressed = archive.totalCompressedBytes();
        long uncompressed = archive.totalUncompressedBytes();
        long saved = Math.max(0, uncompressed - compressed);
        double ratio = uncompressed <= 0 ? 0 : (1.0 - (double) compressed / uncompressed) * 100.0;

        table.add(theme.label("Reports: " + archive.count()
            + "   Stored: " + formatBytes(compressed)
            + "   Uncompressed: " + formatBytes(uncompressed)
            + "   Saved: " + formatBytes(saved) + String.format(Locale.ROOT, " (%.0f%%)", ratio))).expandCellX();
        table.row();
        table.add(theme.horizontalSeparator()).expandX();
        table.row();

        List<ReportMetadata> rows = new ArrayList<>(archive.metadata());
        if (!filter.isEmpty()) {
            rows.removeIf(m -> !m.serverAddress().toLowerCase(Locale.ROOT).contains(filter)
                && !m.id().toLowerCase(Locale.ROOT).contains(filter));
        }
        rows.sort(comparator());

        if (rows.isEmpty()) {
            table.add(theme.label(archive.metadata().isEmpty()
                ? "No archived reports yet."
                : "No reports match the search.")).expandCellX();
            table.row();
            return;
        }

        for (ReportMetadata metadata : rows) {
            table.add(theme.label(date(metadata)));
            table.add(theme.label(metadata.serverAddress().isBlank() ? "unknown" : metadata.serverAddress()));
            table.add(theme.label(metadata.messageCount() + " msgs"));
            table.add(theme.label(formatBytes(metadata.compressedSize())));
            table.add(theme.label(statusText(metadata)).color(statusColor(metadata)));

            WButton open = table.add(theme.button("Open")).right().widget();
            open.action = () -> mc.setScreen(new ReportViewerScreen(theme, archive, metadata.id()));

            WButton delete = table.add(theme.button("Delete")).right().widget();
            delete.action = () -> archive.deleteAsync(metadata.id(), this::refresh);

            table.row();
        }
    }

    private Comparator<ReportMetadata> comparator() {
        Comparator<ReportMetadata> byDate = Comparator.comparingLong(ReportMetadata::generatedAt);
        return sort == Sort.NEWEST ? byDate.reversed() : byDate;
    }

    private static String date(ReportMetadata metadata) {
        long stamp = metadata.generatedAt() > 0 ? metadata.generatedAt() : metadata.endTime();
        return stamp > 0 ? DATE.format(Instant.ofEpochMilli(stamp)) : "unknown";
    }

    private static Color statusColor(ReportMetadata metadata) {
        return switch (metadata.deliveryStatus() == null ? "" : metadata.deliveryStatus()) {
            case "delivered" -> GREEN;
            case "failed" -> RED;
            default -> GREY;
        };
    }

    private static String statusText(ReportMetadata metadata) {
        String status = metadata.deliveryStatus();
        if (status == null || status.isBlank()) return "unknown";
        return status;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.2f MB", mb);
    }

    public enum Sort {
        NEWEST,
        OLDEST;

        Sort next() {
            return this == NEWEST ? OLDEST : NEWEST;
        }

        @Override
        public String toString() {
            return this == NEWEST ? "Newest" : "Oldest";
        }
    }
}
