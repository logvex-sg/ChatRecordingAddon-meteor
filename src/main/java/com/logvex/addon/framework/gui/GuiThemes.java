package com.logvex.addon.framework.gui;

/**
 * Access to the shared {@link GuiTheme}.
 *
 * <p>The theme is a single mutable instance: applying a preset copies colours into it rather than
 * replacing it, so every widget that already holds a reference keeps working and open screens pick
 * the new colours up on their next frame.
 */
public final class GuiThemes {
    private static final GuiTheme THEME = new GuiTheme();
    private static GuiThemePreset preset = GuiThemePreset.DARK;

    private GuiThemes() {
    }

    public static GuiTheme get() {
        return THEME;
    }

    /** The preset currently applied. */
    public static GuiThemePreset preset() {
        return preset;
    }

    /** Applies a preset to the shared theme. */
    public static void applyPreset(GuiThemePreset next) {
        if (next == null) next = GuiThemePreset.DARK;
        preset = next;
        THEME.apply(next.create());
    }
}

