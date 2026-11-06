package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;

import java.util.ArrayList;
import java.util.List;

/**
 * A paginated view of stored chat entries for a query.
 *
 * <p>Results are fetched on the storage worker and rendered one page at a time, so a query that
 * matches a large part of the archive never loads everything into memory.
 */
public class LogViewerScreen extends WindowScreen {
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private static final int PAGE_SIZE = 250;

    private final LogStorageService storage;
    private final LogQuery query;
    private final String title;

    private final List<ChatEntry> entries = new ArrayList<>();
    private int page;

    public LogViewerScreen(LogStorageService storage, LogQuery query, String title) {
        super(GuiThemes.get(), title);
        this.storage = storage;
        this.query = query;
        this.title = title;
    }

    @Override
    public void initWidgets() {
        if (storage == null) {
            add(theme.label("Storage is unavailable.")).expandX();
            return;
        }

        add(theme.label("Loading...")).expandX().widget();

        storage.searchListAsync(query, 5000, this::onLoaded, count -> {});
    }

    private void onLoaded(List<ChatEntry> loaded) {
        entries.clear();
        entries.addAll(loaded);
        render();
    }

    private void render() {
        clear();

        if (entries.isEmpty()) {
            add(theme.label("No matching messages.")).expandX();
            return;
        }

        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, totalPages - 1);

        WVerticalList header = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(header, theme, "Messages:", entries.size() + " (page " + (page + 1) + "/" + totalPages + ")");

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Time")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Type")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Sender")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Message")).expandCellX().widget().color(GuiKit.MUTED);
        table.row();

        int from = page * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            ChatEntry entry = entries.get(i);
            table.add(theme.label(ReportFormatting.shortTime(entry.timestamp()))).widget().color(GuiKit.MUTED);
            table.add(theme.label(entry.type().toString())).widget().color(typeColor(entry));
            table.add(theme.label(entry.sender().isBlank() ? "-" : entry.sender())).widget();
            table.add(theme.label(GuiKit.truncate(entry.content(), 90))).expandCellX().widget();
            table.row();
        }

        if (totalPages > 1) {
            WTable controls = add(theme.table()).expandX().widget();
            WButton prev = controls.add(theme.button("Previous")).widget();
            prev.action = () -> {
                if (page > 0) {
                    page--;
                    render();
                }
            };
            WButton next = controls.add(theme.button("Next")).widget();
            next.action = () -> {
                if (page < totalPages - 1) {
                    page++;
                    render();
                }
            };
        }

        if (entries.size() >= 5000) {
            add(theme.label("Showing the first 5000 matches. Narrow the filter to see more precisely."))
                .expandX().widget().color(GuiKit.WARN);
        }
    }

    private static com.logvex.addon.framework.utils.render.color.Color typeColor(ChatEntry entry) {
        return switch (entry.type()) {
            case PUBLIC_CHAT, PLAYER_MESSAGE -> GuiKit.ACCENT;
            case JOIN -> GuiKit.GOOD;
            case LEAVE -> GuiKit.WARN;
            case DEATH -> GuiKit.BAD;
            default -> GuiKit.MUTED;
        };
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
