package com.logvex.addon.chatrelay.gui;

import com.logvex.addon.chatrelay.core.ChatMessage;
import com.logvex.addon.chatrelay.core.ChatReport;
import com.logvex.addon.chatrelay.core.ReportPayloadBuilder;
import com.logvex.addon.chatrelay.mc.ArchiveService;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;

import java.util.List;
import java.util.Locale;

/**
 * Reads a single archived report. The body is decompressed on a worker thread and only the matching
 * lines are shown, so a large archive never has to be held in memory all at once.
 */
public class ReportViewerScreen extends WindowScreen {
    private static final int MAX_ROWS = 500;

    private final ArchiveService archive;
    private final String reportId;

    private WTextBox search;
    private WTable table;
    private String query = "";

    private ChatReport report;
    private String error;

    public ReportViewerScreen(GuiTheme theme, ArchiveService archive, String reportId) {
        super(theme, "Report " + reportId);
        this.archive = archive;
        this.reportId = reportId;
    }

    @Override
    public void initWidgets() {
        search = add(theme.textBox("", "Search inside this report...")).minWidth(400).expandX().widget();
        search.action = () -> {
            query = search.get().trim().toLowerCase(Locale.ROOT);
            renderMessages();
        };

        add(theme.horizontalSeparator()).expandX();
        table = add(theme.table()).expandX().widget();

        table.add(theme.label("Loading...")).expandCellX();
        table.row();

        archive.loadReportAsync(reportId,
            loaded -> {
                report = loaded;
                renderMessages();
            },
            failure -> {
                error = failure;
                renderMessages();
            });
    }

    private void renderMessages() {
        if (table == null) return;
        table.clear();

        if (error != null) {
            table.add(theme.label("Could not open report: " + error)).expandCellX();
            table.row();
            return;
        }
        if (report == null) {
            table.add(theme.label("Loading...")).expandCellX();
            table.row();
            return;
        }

        table.add(theme.label("Server: " + report.serverAddress()
            + "   Messages: " + report.messageCount()
            + "   Status: " + report.deliveryStatus())).expandCellX();
        table.row();
        table.add(theme.horizontalSeparator()).expandX();
        table.row();

        List<ChatMessage> messages = report.messages();
        int shown = 0;
        for (ChatMessage message : messages) {
            if (!query.isEmpty()) {
                String haystack = (message.sender() + " " + message.content()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) continue;
            }
            table.add(theme.label(ReportPayloadBuilder.format(message))).expandCellX();
            table.row();
            if (++shown >= MAX_ROWS) {
                table.add(theme.label("... " + (messages.size() - shown) + " more messages not shown.")).expandCellX();
                table.row();
                break;
            }
        }

        if (shown == 0) {
            table.add(theme.label(query.isEmpty() ? "This report is empty." : "No messages match the search.")).expandCellX();
            table.row();
        }
    }
}
