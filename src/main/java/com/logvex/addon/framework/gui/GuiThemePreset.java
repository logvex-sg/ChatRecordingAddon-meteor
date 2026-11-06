package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.utils.render.color.Color;

import java.util.Locale;

/**
 * Named colour schemes for the addon's GUI.
 *
 * <p>A preset is just a factory for a {@link GuiTheme}; the live theme is one shared instance whose
 * colours are replaced by {@link GuiThemes#applyPreset}, so switching a preset restyles every open
 * screen on its next frame without any screen having to rebuild.
 *
 * <p>Presets are deliberately a closed set: a user picks "Dark" or "Rose", not six individual
 * hex values, which is what keeps every screen legible no matter who configured it.
 */
public enum GuiThemePreset {
    /** The default dark palette with a blue accent. */
    DARK("Dark", 140, 200, 255),
    /** A light palette for bright rooms. */
    LIGHT("Light", 60, 110, 200),
    /** A near-black palette with a cyan accent. */
    MIDNIGHT("Midnight", 90, 220, 220),
    /** A warm palette with a rose accent. */
    ROSE("Rose", 235, 140, 180),
    /** A muted green palette. */
    FOREST("Forest", 150, 210, 150),
    /** Maximum contrast for accessibility. */
    HIGH_CONTRAST("High Contrast", 255, 255, 255);

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
                theme.text = new Color(30, 30, 36);
                theme.muted = new Color(110, 110, 120);
                theme.button = new Color(220, 220, 228);
                theme.buttonHover = new Color(205, 205, 218);
                theme.textBoxBackground = new Color(245, 245, 248);
                theme.textBoxBorder = new Color(180, 180, 190);
                theme.textBoxFocused = new Color(60, 110, 200);
                theme.separator = new Color(190, 190, 200);
                theme.separatorText = new Color(60, 60, 70);
                theme.windowBackground = new Color(238, 238, 242);
                theme.windowBorder = new Color(150, 150, 160);
                theme.screenBackground = new Color(0, 0, 0, 0x55);
            }
            case MIDNIGHT -> {
                theme.windowBackground = new Color(6, 8, 12);
                theme.button = new Color(24, 30, 38);
                theme.buttonHover = new Color(38, 48, 60);
                theme.textBoxBackground = new Color(14, 18, 24);
            }
            case ROSE -> {
                theme.windowBackground = new Color(26, 16, 22);
                theme.windowBorder = new Color(140, 90, 110);
                theme.button = new Color(56, 36, 46);
                theme.buttonHover = new Color(84, 54, 68);
                theme.textBoxBackground = new Color(34, 20, 28);
            }
            case FOREST -> {
                theme.windowBackground = new Color(14, 20, 16);
                theme.windowBorder = new Color(90, 120, 95);
                theme.button = new Color(38, 50, 40);
                theme.buttonHover = new Color(58, 74, 60);
                theme.textBoxBackground = new Color(20, 28, 22);
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
                // DARK is the field default; only the accent below needs setting.
            }
        }

        Color accent = new Color(accentR, accentG, accentB);
        theme.accent = accent;
        theme.windowTitle = accent;
        theme.textBoxFocused = accent;
        return theme;
    }

    /** Parses a stored name case-insensitively, returning {@code DARK} when it does not match. */
    public static GuiThemePreset parse(String value) {
        if (value == null || value.isBlank()) return DARK;
        String needle = value.trim().toLowerCase(Locale.ROOT);
        for (GuiThemePreset preset : values()) {
            if (preset.name().toLowerCase(Locale.ROOT).equals(needle)) return preset;
            if (preset.title.toLowerCase(Locale.ROOT).equals(needle)) return preset;
        }
        return DARK;
    }
}
