package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.gui.widgets.WHorizontalSeparator;
import com.logvex.addon.framework.gui.widgets.WLabel;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WCard;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.containers.WWindow;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.gui.widgets.pressable.WToggle;
import com.logvex.addon.framework.utils.render.color.Color;

/**
 * Creates widgets and holds the palette they draw with.
 *
 * <p>A single shared instance is used everywhere, so a colour change here restyles every screen.
 * Widget factories stamp the theme onto the widget they return, which is why a widget created
 * through a theme never needs its theme assigned by hand.
 *
 * <p>The palette is mutable so a preset can be applied at runtime: every field is a {@link Color}
 * instance that {@link #apply} replaces in place, and screens pick the new values up on their next
 * frame. The spacing constants keep padding consistent across the whole addon.
 */
public class GuiTheme {
    /** Horizontal padding inside a window. */
    public static final double PAD = 6;
    /** Vertical gap between stacked widgets. */
    public static final double GAP = 2;

    public Color text = new Color(230, 230, 230);
    public Color muted = new Color(150, 150, 150);
    public Color button = new Color(45, 45, 58);
    public Color buttonHover = new Color(70, 70, 90);
    public Color textBoxBackground = new Color(24, 24, 30);
    public Color textBoxBorder = new Color(70, 70, 84);
    public Color textBoxFocused = new Color(120, 190, 255);
    public Color separator = new Color(80, 80, 96);
    public Color separatorText = new Color(190, 190, 200);
    public Color windowBackground = new Color(14, 14, 18);
    public Color windowBorder = new Color(90, 90, 110);
    public Color windowTitle = new Color(140, 200, 255);
    /** Accent used for active tabs, highlighted rows and positive values. */
    public Color accent = new Color(140, 200, 255);
    public Color good = new Color(150, 220, 150);
    public Color warn = new Color(255, 200, 120);
    public Color bad = new Color(255, 140, 140);
    /** Background drawn behind the whole screen, before the window. */
    public Color screenBackground = new Color(0, 0, 0, 0x66);
    /** Fill behind a list row, so rows read as cards rather than floating text. */
    public Color row = new Color(24, 30, 44, 0xB4);
    /** Fill behind a row under the pointer. */
    public Color rowHover = new Color(38, 52, 78, 0xC8);
    /** Fill behind an enabled toggle, in the accent colour. */
    public Color toggleOn = new Color(96, 165, 250);
    /** Fill behind a disabled toggle. */
    public Color toggleOff = new Color(48, 56, 72);
    /** The glow colour drawn around active elements. Usually the accent, often dimmer. */
    public Color glow = new Color(96, 165, 250);
    /** Fill behind the navigation rail on the left of the main window. */
    public Color sidebar = new Color(18, 22, 32, 0xF0);
    /** Fill behind the window's title bar. */
    public Color header = new Color(26, 32, 46, 0xFF);
    /** A second accent used for gradients, so an active bar reads as lit rather than flat. */
    public Color accentSecondary = new Color(147, 197, 253);
    /** Fill behind a selected navigation entry. */
    public Color navSelected = new Color(45, 60, 88, 0xFF);

    /** Copies every colour from {@code other} so a preset applies without replacing the instance. */
    public void apply(GuiTheme other) {
        if (other == null) return;
        text = other.text.copy();
        muted = other.muted.copy();
        button = other.button.copy();
        buttonHover = other.buttonHover.copy();
        textBoxBackground = other.textBoxBackground.copy();
        textBoxBorder = other.textBoxBorder.copy();
        textBoxFocused = other.textBoxFocused.copy();
        separator = other.separator.copy();
        separatorText = other.separatorText.copy();
        windowBackground = other.windowBackground.copy();
        windowBorder = other.windowBorder.copy();
        windowTitle = other.windowTitle.copy();
        accent = other.accent.copy();
        good = other.good.copy();
        warn = other.warn.copy();
        bad = other.bad.copy();
        screenBackground = other.screenBackground.copy();
        row = other.row.copy();
        rowHover = other.rowHover.copy();
        toggleOn = other.toggleOn.copy();
        toggleOff = other.toggleOff.copy();
        glow = other.glow.copy();
        sidebar = other.sidebar.copy();
        header = other.header.copy();
        accentSecondary = other.accentSecondary.copy();
        navSelected = other.navSelected.copy();
    }

    public WLabel label(String text) {
        return stamp(new WLabel(text, this.text));
    }

    public WLabel label(String text, Color color) {
        return stamp(new WLabel(text, color));
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

    /** A labelled on/off switch. */
    public WToggle toggle(String label, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        return stamp(new WToggle(label, getter, setter));
    }

    /** A row card that groups the widgets of one list entry. */
    public WCard card() {
        return stamp(new WCard());
    }

    /** A filled colour block, captioned, for the appearance preview. */
    public com.logvex.addon.framework.gui.widgets.WSwatch swatch(Color color, String caption) {
        return stamp(new com.logvex.addon.framework.gui.widgets.WSwatch(color, caption));
    }

    private <W extends WWidget> W stamp(W widget) {
        widget.theme = this;
        return widget;
    }
}
