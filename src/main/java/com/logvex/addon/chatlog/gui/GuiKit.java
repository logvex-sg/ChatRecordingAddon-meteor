package com.logvex.addon.chatlog.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WLabel;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

/**
 * Small helpers that keep the dashboard screens visually consistent and free of duplicated layout
 * code. Everything here uses only documented Meteor widgets.
 */
final class GuiKit {
    static final Color MUTED = new Color(150, 150, 150);
    static final Color ACCENT = new Color(140, 200, 255);
    static final Color GOOD = new Color(150, 220, 150);
    static final Color WARN = new Color(255, 200, 120);
    static final Color BAD = new Color(255, 140, 140);

    private GuiKit() {
    }

    /** A titled section header row. */
    static void header(WVerticalList list, GuiTheme theme, String title) {
        list.add(theme.horizontalSeparator(title)).expandX();
    }

    static WLabel label(WVerticalList list, GuiTheme theme, String text) {
        return list.add(theme.label(text)).widget();
    }

    static WLabel muted(WVerticalList list, GuiTheme theme, String text) {
        return list.add(theme.label(text)).widget().color(MUTED);
    }

    static WLabel keyValue(WVerticalList list, GuiTheme theme, String key, String value) {
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();
        row.add(theme.label(key)).widget().color(MUTED);
        return row.add(theme.label(value)).expandX().widget();
    }

    static WButton button(WVerticalList list, GuiTheme theme, String text, Runnable action) {
        WButton button = list.add(theme.button(text)).expandX().widget();
        button.action = action;
        return button;
    }

    static WButton button(WHorizontalList row, GuiTheme theme, String text, Runnable action) {
        WButton button = row.add(theme.button(text)).expandX().widget();
        button.action = action;
        return button;
    }

    static void spacer(WVerticalList list, GuiTheme theme) {
        list.add(theme.horizontalSeparator()).expandX();
    }

    static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }
}
