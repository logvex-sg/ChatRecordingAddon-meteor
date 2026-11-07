package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.utils.render.color.Color;

import java.util.Locale;

/**
 * Named colour schemes for the addon's GUI and HUD.
 *
 * <p>A preset is a factory for a {@link GuiTheme}; the live theme is one shared instance whose colours
 * are replaced by {@link GuiThemes#applyPreset}, so switching a preset restyles every open screen and
 * every HUD element on its next frame without any screen having to rebuild.
 *
 * <p>{@link #BLUE} is the default and the addon's signature look: a deep blue-slate window with a
 * vivid blue accent, the same family the HUD panels use. The other presets are variations on that
 * shape rather than unrelated palettes, so the product stays recognisable whichever one is chosen.
 */
public enum GuiThemePreset {
    /** The default: deep blue-slate with a vivid blue accent. */
    BLUE("Blue", 96, 165, 250),
    /** A darker blue with a brighter, colder accent. */
    MIDNIGHT("Midnight", 74, 158, 255),
    /** A soft light-blue palette for bright rooms. */
    AZURE("Azure", 59, 130, 246),
    /** A light palette for maximum readability in daylight. */
    LIGHT("Light", 37, 99, 235),
    /** A purple-leaning variation on the blue theme. */
    VIOLET("Violet", 167, 139, 250),
    /** A teal-leaning variation on the blue theme. */
    TEAL("Teal", 45, 212, 191),
    /** Maximum contrast for accessibility. */
    HIGH_CONTRAST("High Contrast", 255, 255, 255),
    /** A vivid magenta with a warm secondary, the most "client" looking of the set. */
    NEON("Neon", 244, 114, 182),
    /** A saturated green terminal palette. */
    MATRIX("Matrix", 74, 222, 128),
    /** A warm amber palette for a softer look. */
    AMBER("Amber", 251, 191, 36),
    /** A deep crimson palette. */
    CRIMSON("Crimson", 248, 113, 113),
    /** A cool cyan palette. */
    CYAN("Cyan", 34, 211, 238);

    private final String title;
    private final int accentR;
    private final int accentG;
    private final int accentB;

    GuiThemePreset(String title, int accentR, int accentG, int accentB) {
        this.title = title;
        this.accentR = accentR;
        this.accentG = accentG;
        this.accentB = accentB;
    }

    public String title() {
        return title;
    }

    /** Builds a fresh theme carrying this preset's colours. */
    public GuiTheme create() {
        GuiTheme theme = new GuiTheme();
        switch (this) {
            case LIGHT -> {
                theme.text = new Color(24, 28, 38);
                theme.muted = new Color(100, 108, 124);
                theme.button = new Color(226, 232, 244);
                theme.buttonHover = new Color(208, 218, 236);
                theme.textBoxBackground = new Color(248, 250, 254);
                theme.textBoxBorder = new Color(180, 190, 208);
                theme.separator = new Color(196, 204, 220);
                theme.separatorText = new Color(52, 60, 76);
                theme.windowBackground = new Color(240, 244, 252);
                theme.windowBorder = new Color(150, 165, 190);
                theme.screenBackground = new Color(0, 0, 0, 0x55);
            }
            case MIDNIGHT -> {
                theme.windowBackground = new Color(6, 10, 20);
                theme.windowBorder = new Color(40, 62, 104);
                theme.button = new Color(18, 28, 48);
                theme.buttonHover = new Color(30, 46, 78);
                theme.textBoxBackground = new Color(10, 18, 32);
                theme.separator = new Color(40, 58, 92);
            }
            case AZURE -> {
                theme.windowBackground = new Color(16, 26, 44);
                theme.windowBorder = new Color(52, 84, 140);
                theme.button = new Color(26, 44, 74);
                theme.buttonHover = new Color(42, 68, 110);
                theme.textBoxBackground = new Color(18, 32, 54);
                theme.separator = new Color(48, 74, 118);
            }
            case VIOLET -> {
                theme.windowBackground = new Color(20, 16, 34);
                theme.windowBorder = new Color(78, 62, 120);
                theme.button = new Color(40, 32, 62);
                theme.buttonHover = new Color(60, 48, 92);
                theme.textBoxBackground = new Color(26, 22, 44);
                theme.separator = new Color(70, 58, 106);
            }
            case TEAL -> {
                theme.windowBackground = new Color(12, 26, 28);
                theme.windowBorder = new Color(42, 96, 94);
                theme.button = new Color(22, 48, 50);
                theme.buttonHover = new Color(34, 72, 74);
                theme.textBoxBackground = new Color(16, 34, 36);
                theme.separator = new Color(40, 84, 82);
            }
            case HIGH_CONTRAST -> {
                theme.text = new Color(255, 255, 255);
                theme.muted = new Color(200, 200, 200);
                theme.button = new Color(20, 20, 20);
                theme.buttonHover = new Color(60, 60, 60);
                theme.textBoxBackground = new Color(0, 0, 0);
                theme.textBoxBorder = new Color(255, 255, 255);
                theme.separator = new Color(255, 255, 255);
                theme.separatorText = new Color(255, 255, 255);
                theme.windowBackground = new Color(0, 0, 0);
                theme.windowBorder = new Color(255, 255, 255);
                theme.good = new Color(120, 255, 120);
                theme.warn = new Color(255, 220, 120);
                theme.bad = new Color(255, 120, 120);
            }
            default -> {
                // BLUE is the field default; only the accent below needs setting.
            }
        }

        Color accent = new Color(accentR, accentG, accentB);
        theme.accent = accent;
        theme.windowTitle = accent;
        theme.textBoxFocused = accent;
        theme.toggleOn = accent.copy();
        theme.glow = accent.copy();
        // A light or high-contrast scheme needs light rows; a dark one keeps the default dark rows.
        if (this == LIGHT) {
            theme.row = new Color(228, 234, 246, 0xC0);
            theme.rowHover = new Color(212, 222, 240, 0xD8);
            theme.toggleOff = new Color(190, 198, 214);
        } else if (this == HIGH_CONTRAST) {
            theme.row = new Color(0, 0, 0, 0xE0);
            theme.rowHover = new Color(60, 60, 60, 0xE0);
            theme.toggleOff = new Color(60, 60, 60);
        }

        // Derived chrome. Every preset gets a secondary accent (a lighter version of the accent, for
        // gradients) plus matching sidebar, header and selection fills, so a new preset looks complete
        // without having to list each of these by hand.
        theme.accentSecondary = lighten(accent, 0.35);
        boolean light = this == LIGHT;
        theme.sidebar = light ? new Color(226, 232, 246, 0xF0) : new Color(14, 17, 26, 0xF0);
        theme.header = light ? new Color(232, 238, 250, 0xFF) : tint(accent, 26, 0xFF);
        theme.navSelected = light ? new Color(206, 218, 240, 0xFF) : tint(accent, 72, 0xFF);
        return theme;
    }

    /** Blends {@code color} towards white by {@code amount} (0..1). */
    private static Color lighten(Color color, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        return new Color(
            (int) Math.round(color.r + (255 - color.r) * t),
            (int) Math.round(color.g + (255 - color.g) * t),
            (int) Math.round(color.b + (255 - color.b) * t),
            color.a);
    }

    /** A dark surface blended towards {@code accent} by {@code amount}/255. */
    private static Color tint(Color accent, int amount, int alpha) {
        double t = Math.max(0, Math.min(255, amount)) / 255.0;
        int baseR = 16;
        int baseG = 20;
        int baseB = 30;
        return new Color(
            (int) Math.round(baseR + (accent.r - baseR) * t),
            (int) Math.round(baseG + (accent.g - baseG) * t),
            (int) Math.round(baseB + (accent.b - baseB) * t),
            alpha);
    }

    /** Parses a stored name case-insensitively, returning {@code BLUE} when it does not match. */
    public static GuiThemePreset parse(String value) {
        if (value == null || value.isBlank()) return BLUE;
        String needle = value.trim().toLowerCase(Locale.ROOT);
        // Names from the pre-blue builds map onto their nearest replacement so a stored choice survives.
        switch (needle) {
            case "dark" -> {
                return BLUE;
            }
            case "rose" -> {
                return VIOLET;
            }
            case "forest" -> {
                return TEAL;
            }
            default -> {
            }
        }
        for (GuiThemePreset preset : values()) {
            if (preset.name().toLowerCase(Locale.ROOT).equals(needle)) return preset;
            if (preset.title.toLowerCase(Locale.ROOT).equals(needle)) return preset;
        }
        return BLUE;
    }
}
