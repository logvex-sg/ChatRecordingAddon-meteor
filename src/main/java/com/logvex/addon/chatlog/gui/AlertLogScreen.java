package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.AlertLog;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;

/**
 * A searchable view of the {@link AlertLog}: keyword hits, webhook delivery problems and internal
 * errors, newest first. This is the place a message that scrolled past chat can be recovered.
 */
public class AlertLogScreen extends WindowScreen {
    private final LogService service = LogService.get();
    private final String title;
    private String filter = "";

    public AlertLogScreen(String title) {
        super(GuiThemes.get(), title);
        this.title = title;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        AlertLog log = service.alerts();

        var search = add(theme.textBox(filter, "Search alerts...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            taskAfterRender = this::render;
        };

        WTable summary = add(theme.table()).expandX().widget();
        summary.add(theme.label("Keyword")).widget().color(GuiKit.MUTED);
        summary.add(theme.label("Webhook")).widget().color(GuiKit.MUTED);
        summary.add(theme.label("Error")).widget().color(GuiKit.MUTED);
        summary.add(theme.label("Total")).widget().color(GuiKit.MUTED);
        summary.row();
        summary.add(theme.label(Integer.toString(log.count(AlertLog.Kind.KEYWORD)))).widget().color(GuiKit.ACCENT);
        summary.add(theme.label(Integer.toString(log.count(AlertLog.Kind.WEBHOOK)))).widget().color(GuiKit.WARN);
        summary.add(theme.label(Integer.toString(log.count(AlertLog.Kind.ERROR)))).widget().color(GuiKit.BAD);
        summary.add(theme.label(Long.toString(log.totalRecorded()))).widget();

        WButton clear = add(theme.button("Clear alerts")).expandX().widget();
        clear.action = () -> {
            log.clear();
            info("Alert history cleared.");
            render();
        };

        add(theme.horizontalSeparator("Alerts")).expandX();

        List<AlertLog.Alert> alerts = log.recent(filter, 500);
        if (alerts.isEmpty()) {
            add(theme.label(filter.isBlank() ? "No alerts recorded yet." : "No alerts match the search."))
                .expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Time")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Kind")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Title")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Detail")).expandCellX().widget().color(GuiKit.MUTED);
        table.row();

        for (AlertLog.Alert alert : alerts) {
            table.add(theme.label(ReportFormatting.shortTime(alert.timestamp()))).widget().color(GuiKit.MUTED);
            table.add(theme.label(alert.kind().toString())).widget().color(colorFor(alert.kind()));
            table.add(theme.label(GuiKit.truncate(alert.title(), 28))).widget();
            table.add(theme.label(GuiKit.truncate(alert.detail(), 90))).expandCellX().widget();
            table.row();
        }
    }

    private static Color colorFor(AlertLog.Kind kind) {
        return switch (kind) {
            case KEYWORD -> GuiKit.ACCENT;
            case WEBHOOK -> GuiKit.WARN;
            case ERROR -> GuiKit.BAD;
        };
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
