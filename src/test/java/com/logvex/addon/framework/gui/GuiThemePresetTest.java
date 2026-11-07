package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.systems.config.ClientConfig;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.utils.render.color.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GUI colour scheme is user-visible state that must survive a restart and must never leave the
 * theme in an unusable state, so these tests cover the preset factory, the apply path and the config
 * round-trip.
 */
class GuiThemePresetTest {

    private final ConfigManager config = ConfigManager.get();

    @AfterEach
    void tearDown() {
        config.setFile(null);
        GuiThemes.applyPreset(GuiThemePreset.BLUE);
    }

    @Test
    @DisplayName("every preset builds a complete theme with no null colours")
    void everyPresetIsComplete() {
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            GuiTheme theme = preset.create();
            assertNotNull(theme, preset + " must build a theme");
            for (Color c : new Color[]{theme.text, theme.muted, theme.button, theme.buttonHover,
                theme.textBoxBackground, theme.textBoxBorder, theme.textBoxFocused, theme.separator,
                theme.separatorText, theme.windowBackground, theme.windowBorder, theme.windowTitle,
                theme.accent, theme.good, theme.warn, theme.bad, theme.screenBackground,
                theme.row, theme.rowHover, theme.toggleOn, theme.toggleOff}) {
                assertNotNull(c, preset + " left a colour null");
            }
        }
    }

    @Test
    @DisplayName("the default theme is blue")
    void defaultIsBlue() {
        assertEquals(GuiThemePreset.BLUE, GuiThemePreset.parse(null));
        assertTrue(GuiThemePreset.BLUE.create().accent.b > GuiThemePreset.BLUE.create().accent.r,
            "the signature accent must lean blue");
    }

    @Test
    @DisplayName("presets have distinct accents so switching one is visible")
    void accentsDiffer() {
        assertNotEquals(GuiThemePreset.BLUE.create().accent.r, GuiThemePreset.VIOLET.create().accent.r);
        assertNotEquals(GuiThemePreset.BLUE.create().windowBackground.r,
            GuiThemePreset.LIGHT.create().windowBackground.r);
    }

    @Test
    @DisplayName("applying a preset mutates the shared instance in place")
    void applyPresetMutatesInPlace() {
        GuiTheme shared = GuiThemes.get();
        Color before = shared.windowBackground.copy();

        GuiThemes.applyPreset(GuiThemePreset.LIGHT);

        // The instance is the same object; only its colours changed. This is what lets an already
        // open screen restyle itself without rebuilding.
        assertSame(shared, GuiThemes.get());
        assertNotEquals(before.r, shared.windowBackground.r);
        assertEquals(GuiThemePreset.LIGHT, GuiThemes.preset());
    }

    @Test
    @DisplayName("a stored name parses back to the same preset, unknown names fall back to BLUE")
    void parseRoundTrips() {
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            assertEquals(preset, GuiThemePreset.parse(preset.name()));
            assertEquals(preset, GuiThemePreset.parse(preset.title().toLowerCase(Locale.ROOT)));
        }
        assertEquals(GuiThemePreset.BLUE, GuiThemePreset.parse("no-such-theme"));
        assertEquals(GuiThemePreset.BLUE, GuiThemePreset.parse(null));
        assertEquals(GuiThemePreset.BLUE, GuiThemePreset.parse(""));
    }

    @Test
    @DisplayName("names from the pre-blue builds migrate onto their nearest replacement")
    void oldNamesMigrate() {
        // A config written before the blue rework named the old presets; they must not fall back to
        // an arbitrary default, or the user's choice would silently change.
        assertEquals(GuiThemePreset.BLUE, GuiThemePreset.parse("DARK"));
        assertEquals(GuiThemePreset.VIOLET, GuiThemePreset.parse("ROSE"));
        assertEquals(GuiThemePreset.TEAL, GuiThemePreset.parse("FOREST"));
    }

    @Test
    @DisplayName("the chosen preset survives a save and load")
    void presetPersists(@TempDir Path dir) {
        Path file = dir.resolve("config.json");
        config.setFile(file);

        ClientConfig.get().setThemePreset(GuiThemePreset.VIOLET);
        GuiThemes.applyPreset(GuiThemePreset.VIOLET);
        config.save();

        // Simulate a restart: reset to the default, then load the file back over it.
        ClientConfig.get().setThemePreset(GuiThemePreset.BLUE);
        GuiThemes.applyPreset(GuiThemePreset.BLUE);

        config.load();

        assertEquals(GuiThemePreset.VIOLET, ClientConfig.get().themePreset(),
            "the theme choice should be restored");
        assertEquals(GuiThemePreset.VIOLET, GuiThemes.preset(),
            "loading must re-apply the saved theme to the shared instance");
    }
}
