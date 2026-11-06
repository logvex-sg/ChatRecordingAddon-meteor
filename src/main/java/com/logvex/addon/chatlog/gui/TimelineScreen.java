package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.SessionTimeline;
import com.logvex.addon.chatlog.modules.SessionTimelineModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;

/**
 * Browse the session timeline with a text filter.
 *
 * <p>Only one page of rows is built at a time, so a long session stays cheap to render.
 */
public class TimelineScreen extends WindowScreen {
    private static final int PAGE_SIZE = 200;

    private final SessionTimelineModule module;
    private String filter = "";
    private int page;

    public TimelineScreen(SessionTimelineModule module) {
        super(GuiThemes.get(), "Session Timeline");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        WTextBox search = add(theme.textBox(filter, "Filter events...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            page = 0;
            render();
        };

        WTable kinds = add(theme.table()).expandX().widget();
        for (SessionTimeline.Kind kind : SessionTimeline.Kind.values()) {
            kinds.add(theme.label(kind.label() + ": " + module.timeline().count(kind)))
                .widget().color(GuiKit.MUTED);
        }
        kinds.row();

        List<SessionTimeline.Event> events = module.events(filter);
        if (events.isEmpty()) {
            add(theme.label(filter.isBlank() ? "No milestones yet." : "No events match that filter."))
                .expandX().widget().color(GuiKit.MUTED);
            return;
        }

        int totalPages = Math.max(1, (events.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, totalPages - 1);

        WVerticalList header = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(header, theme, "Milestones:",
            events.size() + " (page " + (page + 1) + "/" + totalPages + ")");

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Time")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Kind")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Detail")).expandCellX().widget().color(GuiKit.MUTED);
        table.row();

        int from = page * PAGE_SIZE;
        int to = Math.min(events.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            SessionTimeline.Event event = events.get(i);
            table.add(theme.label(Messages.clock(event.timestamp()))).widget().color(GuiKit.MUTED);
            table.add(theme.label(event.kind().label())).widget().color(kindColor(event.kind()));
            table.add(theme.label(GuiKit.truncate(event.line(), 90))).expandCellX().widget();
            table.row();
        }

        WTable controls = add(theme.table()).expandX().widget();
        if (totalPages > 1) {
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
        WButton clear = controls.add(theme.button("Clear")).widget();
        clear.action = () -> {
            module.timeline().clear();
            page = 0;
            render();
        };
    }

    private static Color kindColor(SessionTimeline.Kind kind) {
        return switch (kind) {
            case JOIN, SESSION_START -> GuiKit.GOOD;
            case LEAVE, SESSION_END -> GuiKit.WARN;
            case DEATH, WARNING -> GuiKit.BAD;
            case KEYWORD -> GuiKit.ACCENT;
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
