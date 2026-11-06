package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.systems.config.ClientConfig;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.utils.render.color.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        GuiThemes.applyPreset(GuiThemePreset.DARK);
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
                theme.accent, theme.good, theme.warn, theme.bad, theme.screenBackground}) {
                assertNotNull(c, preset + " left a colour null");
            }
        }
    }

    @Test
    @DisplayName("presets have distinct accents so switching one is visible")
    void accentsDiffer() {
        assertNotEquals(GuiThemePreset.DARK.create().accent.r, GuiThemePreset.ROSE.create().accent.r);
        assertNotEquals(GuiThemePreset.DARK.create().windowBackground.r,
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
        assertTrue(shared == GuiThemes.get());
        assertNotEquals(before.r, shared.windowBackground.r);
        assertEquals(GuiThemePreset.LIGHT, GuiThemes.preset());
    }

    @Test
    @DisplayName("a stored name parses back to the same preset, unknown names fall back to DARK")
    void parseRoundTrips() {
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            assertEquals(preset, GuiThemePreset.parse(preset.name()));
            assertEquals(preset, GuiThemePreset.parse(preset.title().toLowerCase(java.util.Locale.ROOT)));
        }
        assertEquals(GuiThemePreset.DARK, GuiThemePreset.parse("no-such-theme"));
        assertEquals(GuiThemePreset.DARK, GuiThemePreset.parse(null));
        assertEquals(GuiThemePreset.DARK, GuiThemePreset.parse(""));
    }

    @Test
    @DisplayName("the chosen preset survives a save and load")
    void presetPersists(@TempDir Path dir) {
        Path file = dir.resolve("config.json");
        config.setFile(file);

        ClientConfig.get().setThemePreset(GuiThemePreset.ROSE);
        GuiThemes.applyPreset(GuiThemePreset.ROSE);
        config.save();

        // Simulate a restart: reset to the default, then load the file back over it.
        ClientConfig.get().setThemePreset(GuiThemePreset.DARK);
        GuiThemes.applyPreset(GuiThemePreset.DARK);

        config.load();

        assertEquals(GuiThemePreset.ROSE, ClientConfig.get().themePreset(),
            "the theme choice should be restored");
        assertEquals(GuiThemePreset.ROSE, GuiThemes.preset(),
            "loading must re-apply the saved theme to the shared instance");
    }
}
