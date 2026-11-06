package com.logvex.addon.notify.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.notify.NotificationModule;
import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;

/**
 * The notification history browser.
 *
 * <p>Every value is read from the shared centre on demand, so the screen shows the real stream and
 * clearing here clears what the HUD and commands see too. Search and the priority filter run against
 * the centre's bounded history, so the screen can never load an unbounded amount of data.
 */
public class NotificationCenterScreen extends WindowScreen {
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color ACCENT = new Color(140, 200, 255);
    private static final Color WARN = new Color(255, 200, 120);
    private static final Color BAD = new Color(255, 140, 140);
    private static final Color GOOD = new Color(150, 220, 150);

    private static final int MAX_ROWS = 200;

    private final NotificationModule module;
    private String search = "";
    private NotificationPriority minPriority = NotificationPriority.LOW;

    public NotificationCenterScreen(NotificationModule module) {
        super(GuiThemes.get(), "Notification Center");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        if (module == null) {
            add(theme.label("The notifications module is unavailable.")).expandX();
            return;
        }

        WVerticalList header = add(theme.verticalList()).expandX().widget();
        header.add(theme.horizontalSeparator("Search")).expandX();
        WTextBox box = header.add(theme.textBox(search, "Search notifications...")).expandX().widget();
        box.action = () -> {
            search = box.get();
            render();
        };

        WTable filters = add(theme.table()).expandX().widget();
        for (NotificationPriority priority : NotificationPriority.values()) {
            boolean selected = priority == minPriority;
            WButton button = filters.add(theme.button((selected ? "\u00bb " : "") + priority.name())).widget();
            button.action = () -> {
                minPriority = priority;
                render();
            };
        }
        filters.row();

        List<Notification> all = module.center().search(search, 500);
        List<Notification> shown = all.stream()
            .filter(n -> n.priority().atLeast(minPriority))
            .limit(MAX_ROWS)
            .toList();

        add(theme.horizontalSeparator()).expandX();
        WVerticalList summary = add(theme.verticalList()).expandX().widget();
        summary.add(theme.label("Showing " + shown.size() + " of " + module.center().size()
            + " stored  \u2022  accepted " + module.center().accepted()
            + ", suppressed " + module.center().suppressed())).expandX().widget().color(MUTED);

        if (shown.isEmpty()) {
            add(theme.label(module.center().isEmpty()
                ? "No notifications yet. Every Log Addon feature reports here."
                : "No notifications match this filter.")).expandX().widget().color(MUTED);
        } else {
            WTable table = add(theme.table()).expandX().widget();
            table.add(theme.label("Time")).widget().color(MUTED);
            table.add(theme.label("Priority")).widget().color(MUTED);
            table.add(theme.label("Source")).widget().color(MUTED);
            table.add(theme.label("Message")).expandCellX().widget().color(MUTED);
            table.row();

            for (Notification notification : shown) {
                table.add(theme.label(Messages.clock(notification.timestamp()))).widget().color(MUTED);
                table.add(theme.label(notification.priority().name())).widget()
                    .color(colorFor(notification.priority()));
                table.add(theme.label(notification.source().isEmpty() ? "-" : notification.source())).widget();
                String body = notification.title().isEmpty() ? notification.message()
                    : (notification.message().isEmpty()
                        ? notification.title()
                        : notification.title() + " " + notification.message());
                table.add(theme.label(Messages.truncate(body, 80))).expandCellX().widget();
                table.row();
            }
        }

        if (all.size() > MAX_ROWS) {
            add(theme.label("Showing the most recent " + MAX_ROWS + " matches. Narrow the search."))
                .expandX().widget().color(WARN);
        }

        WTable controls = add(theme.table()).expandX().widget();
        WButton clear = controls.add(theme.button("Clear History")).widget();
        clear.action = () -> {
            module.center().clear();
            render();
        };
        WButton refresh = controls.add(theme.button("Refresh")).widget();
        refresh.action = this::render;
        controls.row();
    }

    private static Color colorFor(NotificationPriority priority) {
        return switch (priority) {
            case CRITICAL -> BAD;
            case HIGH -> WARN;
            case NORMAL -> GOOD;
            case LOW -> MUTED;
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
