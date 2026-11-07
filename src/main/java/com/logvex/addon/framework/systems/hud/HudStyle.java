package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.framework.utils.render.color.SettingColor;

/**
 * The colours a HUD element draws with, after resolving its "use the theme" options.
 *
 * <p>Elements share a palette so the whole HUD looks like one product instead of eleven unrelated
 * boxes. An element that wants a custom colour turns off the matching "use theme colour" toggle and
 * its own setting wins; otherwise the colour comes from the active GUI theme, so switching to the
 * blue theme restyles the HUD as well as the screens.
 */
public record HudStyle(
    Color background,
    Color border,
    Color title,
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
    /** Builds a style from an element's settings, falling back to the theme for anything unset. */
    public static HudStyle resolve(boolean customBackground, SettingColor background,
                                   boolean border, SettingColor borderColor,
                                   boolean customTitle, SettingColor title,
                                   boolean customText, SettingColor text,
                                   boolean customAccent, SettingColor accent,
                                   HudThemeColors theme) {
        return new HudStyle(
            customBackground && background != null ? background.copy() : theme.background(),
            border && borderColor != null ? borderColor.copy() : theme.border(),
            customTitle && title != null ? title.copy() : theme.accent(),
            customText && text != null ? text.copy() : theme.text(),
            theme.muted(),
            customAccent && accent != null ? accent.copy() : theme.accent(),
            theme.accentSecondary(),
            theme.glow(),
            theme.good(),
            theme.warn(),
            theme.bad(),
            theme.track());
    }

    /** Convenience for an element that only overrides a background and everything else follows the theme. */
    public static HudStyle theme(HudThemeColors theme) {
        return new HudStyle(theme.background(), theme.border(), theme.accent(), theme.text(),
            theme.muted(), theme.accent(), theme.accentSecondary(), theme.glow(),
            theme.good(), theme.warn(), theme.bad(), theme.track());
    }
}
