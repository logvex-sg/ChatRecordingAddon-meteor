package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.LogImporter;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.framework.MeteorClient;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.schematicdropper.gui.FileBrowserScreen;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Imports an existing log file into the archive.
 *
 * <p>The user picks a file through the built-in browser, the file is parsed off the client thread,
 * and a preview is shown before anything is written: the detected format, how many entries are
 * usable, which lines were skipped and why, and the first and last entry so the range is obvious.
 * Only "Import into archive" merges the entries.
 */
public class LogImportScreen extends WindowScreen {
    private static final int PREVIEW_LIMIT = 100;

    private final LogStorageService storage;

    private LogImporter.Result result;
    private String chosenName = "";
    private String error = "";
    private boolean busy;
    private int page;

    public LogImportScreen(LogStorageService storage) {
        super(GuiThemes.get(), "Import Log");
        this.storage = storage;
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

        add(theme.label("Import an exported log or a stored segment back into the archive."))
            .expandX().widget().color(GuiKit.MUTED);
        add(theme.label("Supported: .txt, .csv, .json and native .log.gz segments."))
            .expandX().widget().color(GuiKit.MUTED);

        WTable actions = add(theme.table()).expandX().widget();
        WButton choose = actions.add(theme.button("Choose file...")).widget();
        choose.action = this::chooseFile;

        if (busy) {
            add(theme.label("Reading " + chosenName + "...")).expandX().widget().color(GuiKit.ACCENT);
            return;
        }
        if (!error.isEmpty()) {
            add(theme.horizontalSeparator("Error")).expandX();
            add(theme.label(error)).expandX().widget().color(GuiKit.BAD);
        }
        if (result == null) return;

        add(theme.horizontalSeparator("Preview: " + chosenName)).expandX();
        var summary = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(summary, theme, "Format:", result.format());
        GuiKit.keyValue(summary, theme, "Entries:", Integer.toString(result.entries().size()));
        GuiKit.keyValue(summary, theme, "Skipped lines:", Integer.toString(result.skipped().size()));
        if (!result.entries().isEmpty()) {
            GuiKit.keyValue(summary, theme, "First:", firstLine(result.entries().get(0)));
            GuiKit.keyValue(summary, theme, "Last:", firstLine(result.entries().get(result.entries().size() - 1)));
        }

        WTable importRow = add(theme.table()).expandX().widget();
        WButton importButton = importRow.add(theme.button("Import into archive")).widget();
        importButton.action = this::importNow;
        WButton discard = importRow.add(theme.button("Discard")).widget();
        discard.action = () -> {
            result = null;
            error = "";
            render();
        };

        if (!result.skipped().isEmpty()) {
            add(theme.horizontalSeparator("Skipped lines (" + result.skipped().size() + ")")).expandX();
            int shown = Math.min(result.skipped().size(), 20);
            for (int i = 0; i < shown; i++) {
                add(theme.label("  " + result.skipped().get(i))).expandX().widget().color(GuiKit.WARN);
            }
            if (result.skipped().size() > shown) {
                add(theme.label("  ... and " + (result.skipped().size() - shown) + " more."))
                    .expandX().widget().color(GuiKit.MUTED);
            }
        }

        renderEntries();
    }

    private void renderEntries() {
        List<ChatEntry> entries = result.entries();
        if (entries.isEmpty()) return;

        add(theme.horizontalSeparator("Entries")).expandX();
        int totalPages = Math.max(1, (entries.size() + PREVIEW_LIMIT - 1) / PREVIEW_LIMIT);
        page = Math.min(page, totalPages - 1);

        WTable nav = add(theme.table()).expandX().widget();
        WButton prev = nav.add(theme.button("Prev")).widget();
        prev.action = () -> {
            page = Math.max(0, page - 1);
            render();
        };
        nav.add(theme.label("Page " + (page + 1) + " / " + totalPages)).widget().color(GuiKit.MUTED);
        WButton next = nav.add(theme.button("Next")).widget();
        next.action = () -> {
            page = Math.min(totalPages - 1, page + 1);
            render();
        };

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Time")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Type")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Sender")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Message")).expandCellX().widget().color(GuiKit.MUTED);
        table.row();

        int from = page * PREVIEW_LIMIT;
        int to = Math.min(entries.size(), from + PREVIEW_LIMIT);
        for (int i = from; i < to; i++) {
            ChatEntry entry = entries.get(i);
            table.add(theme.label(ReportFormatting.shortTime(entry.timestamp()))).widget().color(GuiKit.MUTED);
            table.add(theme.label(entry.type().toString())).widget();
            table.add(theme.label(entry.sender().isBlank() ? "-" : entry.sender())).widget();
            table.add(theme.label(GuiKit.truncate(entry.content(), 90))).expandCellX().widget();
            table.row();
        }
    }

    private void chooseFile() {
        Path root = storage.root();
        File start = root == null ? new File(".") : root.toFile();
        MeteorClient.mc.setScreen(new FileBrowserScreen(start, "Choose a log file",
            List.of(".txt", ".csv", ".json", ".gz", ".log"), this::parse));
    }

    private void parse(File file) {
        busy = true;
        chosenName = file.getName();
        error = "";
        result = null;
        render();
        storage.importLogAsync(file.toPath(), parsed -> {
            busy = false;
            result = parsed;
            page = 0;
            render();
        }, message -> {
            busy = false;
            error = "Could not read the file: " + message;
            render();
        });
    }

    private void importNow() {
        if (result == null || result.entries().isEmpty()) return;
        List<ChatEntry> entries = result.entries();
        info("Importing " + entries.size() + " entries into the archive.");
        storage.importEntriesAsync(entries, () -> info("Import finished."));
        result = null;
        render();
    }

    private void info(String message) {
        com.logvex.addon.framework.utils.player.ChatUtils.info("%s", message);
    }

    private static String firstLine(ChatEntry entry) {
        String sender = entry.sender().isBlank() ? entry.type().toString() : entry.sender();
        return String.format(Locale.ROOT, "%s %s: %s", ReportFormatting.shortTime(entry.timestamp()),
            sender, GuiKit.truncate(entry.content(), 40));
    }
}
