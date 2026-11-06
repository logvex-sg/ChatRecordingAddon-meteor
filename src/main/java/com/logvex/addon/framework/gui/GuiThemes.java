package com.logvex.addon.framework.gui;

/** Access to the shared {@link GuiTheme}. */
public final class GuiThemes {
    private static final GuiTheme THEME = new GuiTheme();

    private GuiThemes() {
    }

    public static GuiTheme get() {
        return THEME;
    }
}
