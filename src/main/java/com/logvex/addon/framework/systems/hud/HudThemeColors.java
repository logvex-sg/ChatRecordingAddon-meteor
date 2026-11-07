package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.utils.render.color.Color;

/**
 * The HUD's slice of the active GUI theme.
 *
 * <p>HUD elements and the GUI therefore draw from one palette: picking the blue theme in the GUI
 * recolours the HUD too. The panel background is deliberately darker and more transparent than a
 * GUI window so a HUD box stays readable over the world without hiding it.
 */
public record HudThemeColors(
    Color background,
    Color border,
    Color text,
    Color muted,
    Color accent,
    Color accentSecondary,
    Color glow,
    Color good,
    Color warn,
    Color bad,
    Color track
) {
    /** The palette of the theme currently applied to the GUI. */
    public static HudThemeColors current() {
        GuiTheme theme = GuiThemes.get();
        return new HudThemeColors(
            new Color(theme.windowBackground.r, theme.windowBackground.g, theme.windowBackground.b, 0xB4),
            new Color(theme.windowBorder.r, theme.windowBorder.g, theme.windowBorder.b, 0xD2),
            theme.text.copy(),
            theme.muted.copy(),
            theme.accent.copy(),
            theme.accentSecondary.copy(),
            theme.glow.copy(),
            theme.good.copy(),
            theme.warn.copy(),
            theme.bad.copy(),
            new Color(theme.separator.r, theme.separator.g, theme.separator.b, 0xC0));
    }

    /** True when two palettes differ, used by tests to prove a theme switch reaches the HUD. */
    public boolean differsFrom(HudThemeColors other) {
        if (other == null) return true;
        return !accent.equals(other.accent) || !background.equals(other.background);
    }
}
