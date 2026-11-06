package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.gui.widgets.WHorizontalSeparator;
import com.logvex.addon.framework.gui.widgets.WLabel;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.containers.WWindow;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

/**
 * Creates widgets and holds the palette they draw with.
 *
 * <p>A single shared instance is used everywhere, so a colour change here restyles every screen.
 * Widget factories stamp the theme onto the widget they return, which is why a widget created
 * through a theme never needs its theme assigned by hand.
 */
public class GuiTheme {
    public final Color text = new Color(230, 230, 230);
    public final Color muted = new Color(150, 150, 150);
    public final Color button = new Color(45, 45, 58);
    public final Color buttonHover = new Color(70, 70, 90);
    public final Color textBoxBackground = new Color(24, 24, 30);
    public final Color textBoxBorder = new Color(70, 70, 84);
    public final Color textBoxFocused = new Color(120, 190, 255);
    public final Color separator = new Color(80, 80, 96);
    public final Color separatorText = new Color(190, 190, 200);
    public final Color windowBackground = new Color(14, 14, 18);
    public final Color windowBorder = new Color(90, 90, 110);
    public final Color windowTitle = new Color(140, 200, 255);

    public WLabel label(String text) {
        return stamp(new WLabel(text, this.text));
    }

    public WButton button(String text) {
        return stamp(new WButton(text, null));
    }

    public WButton button(String text, Runnable action) {
        return stamp(new WButton(text, action));
    }

    public WTextBox textBox(String text) {
        return stamp(new WTextBox(text, ""));
    }

    public WTextBox textBox(String text, String placeholder) {
        return stamp(new WTextBox(text, placeholder));
    }

    public WHorizontalSeparator horizontalSeparator() {
        return stamp(new WHorizontalSeparator(""));
    }

    public WHorizontalSeparator horizontalSeparator(String text) {
        return stamp(new WHorizontalSeparator(text));
    }

    public WTable table() {
        return stamp(new WTable());
    }

    public WVerticalList verticalList() {
        return stamp(new WVerticalList());
    }

    public WHorizontalList horizontalList() {
        return stamp(new WHorizontalList());
    }

    public WWindow window(String title) {
        WWindow window = stamp(new WWindow(title));
        window.view.theme = this;
        return window;
    }

    private <W extends WWidget> W stamp(W widget) {
        widget.theme = this;
        return widget;
    }
}
