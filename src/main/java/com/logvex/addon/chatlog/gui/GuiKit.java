package com.logvex.addon.chatlog.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.widgets.WLabel;
import com.logvex.addon.framework.gui.widgets.containers.WCard;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Small helpers that keep the dashboard screens visually consistent and free of duplicated layout
 * code. Everything here uses only the addon's own widgets.
 */
final class GuiKit {
    private GuiKit() {
    }

    // These read the shared theme instead of holding their own colours. A screen rebuilds its
    // widgets on every frame, so an accessor returns the preset's current colour; a static field
    // would keep whatever the colour was when the class was first loaded and ignore a preset change.

    /** Secondary text: column headers, timestamps, explanatory notes. */
    static Color mutedColor() {
        return GuiThemes.get().muted;
    }

    /** The theme accent, for values worth reading first. */
    static Color accentColor() {
        return GuiThemes.get().accent;
    }

    static Color goodColor() {
        return GuiThemes.get().good;
    }

    static Color warnColor() {
        return GuiThemes.get().warn;
    }

    static Color badColor() {
        return GuiThemes.get().bad;
    }

    /**
     * A block of {@code key: value} rows whose values all start in the same column.
     *
     * <p>One table, not one row per key: the table sizes its key column from the widest key, so
     * {@code Server} and {@code Automatic cleanup} line their values up. Stacking separate rows
     * instead leaves every value at a different x, which is what makes a list of facts hard to scan.
     */
    static void facts(WVerticalList list, GuiTheme theme, String... keyValuePairs) {
        if (keyValuePairs.length < 2) return;
        WTable table = list.add(theme.table()).expandX().widget();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            table.add(theme.label(keyValuePairs[i])).widget().color(mutedColor());
            table.add(theme.label(keyValuePairs[i + 1])).expandCellX().widget();
            table.row();
        }
        table.finish();
    }

    /**
     * A small figure with a caption under it, in a filled card.
     *
     * <p>For the handful of numbers a screen exists to show — how many segments, how much space —
     * a tile reads faster than another key/value row. The value takes the tone colour so an
     * unhealthy figure stands out without a separate warning line.
     */
    static void tiles(WVerticalList list, GuiTheme theme, Tile... tiles) {
        if (tiles.length == 0) return;
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();
        for (Tile tile : tiles) {
            WCard card = row.add(theme.card()).expandX().widget();
            WVerticalList stack = theme.verticalList();
            WLabel figure = stack.add(theme.label(tile.value)).expandX().widget();
            figure.color(tile.tone == null ? theme.text : tile.tone);
            if (tile.caption != null && !tile.caption.isBlank()) {
                stack.add(theme.label(tile.caption)).expandX().widget().color(mutedColor());
            }
            card.add(stack).expandX();
        }
    }

    /** One figure on a tile. */
    record Tile(String value, String caption, Color tone) {
        static Tile of(String value, String caption) {
            return new Tile(value, caption, null);
        }

        static Tile of(String value, String caption, Color tone) {
            return new Tile(value, caption, tone);
        }
    }

    static WLabel muted(WVerticalList list, GuiTheme theme, String text) {
        return list.add(theme.label(text)).widget().color(mutedColor());
    }

    static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }
}
