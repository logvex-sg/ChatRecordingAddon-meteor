package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.utils.render.color.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chrome colours a preset derives on top of its accent.
 *
 * <p>Every preset has to produce a complete, usable theme: a sidebar and header that are visible
 * against the window, a glow, and a secondary accent for gradients. These tests fail if a new preset
 * is added without those, which would otherwise show up as an invisible rail or a black gradient.
 */
class GuiThemeChromeTest {

    @AfterEach
    void tearDown() {
        GuiThemes.applyPreset(GuiThemePreset.BLUE);
    }

    @Test
    @DisplayName("every preset fills in the chrome colours")
    void everyPresetHasChrome() {
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            GuiTheme theme = preset.create();
            for (Color colour : new Color[]{
                theme.glow, theme.sidebar, theme.header, theme.accentSecondary, theme.navSelected}) {
                assertNotNull(colour, preset + " left a chrome colour null");
            }
        }
    }

    @Test
    @DisplayName("the glow follows the accent so a preset's halo matches its buttons")
    void glowMatchesAccent() {
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            GuiTheme theme = preset.create();
            assertEquals(theme.accent.r, theme.glow.r, preset + " glow red should follow the accent");
            assertEquals(theme.accent.g, theme.glow.g, preset + " glow green should follow the accent");
            assertEquals(theme.accent.b, theme.glow.b, preset + " glow blue should follow the accent");
        }
    }

    @Test
    @DisplayName("the secondary accent is lighter than the accent, for a visible gradient")
    void secondaryIsLighter() {
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            GuiTheme theme = preset.create();
            int accentSum = theme.accent.r + theme.accent.g + theme.accent.b;
            int secondarySum = theme.accentSecondary.r + theme.accentSecondary.g + theme.accentSecondary.b;
            assertTrue(secondarySum >= accentSum,
                preset + " secondary accent should not be darker than the accent");
        }
    }

    @Test
    @DisplayName("apply copies the chrome colours into the shared instance")
    void applyCopiesChrome() {
        GuiTheme shared = GuiThemes.get();
        GuiTheme source = GuiThemePreset.NEON.create();

        GuiThemes.applyPreset(GuiThemePreset.NEON);

        // The instance is reused; only its colours change. A screen holding a reference must see the
        // new chrome on its next frame.
        assertEquals(source.sidebar.r, shared.sidebar.r);
        assertEquals(source.accentSecondary.b, shared.accentSecondary.b);
        assertEquals(source.navSelected.g, shared.navSelected.g);
    }

    @Test
    @DisplayName("the new presets are distinct from the default so switching one is visible")
    void newPresetsAreDistinct() {
        Color blue = GuiThemePreset.BLUE.create().accent;
        for (GuiThemePreset preset : new GuiThemePreset[]{
            GuiThemePreset.NEON, GuiThemePreset.MATRIX, GuiThemePreset.AMBER,
            GuiThemePreset.CRIMSON, GuiThemePreset.CYAN}) {
            assertNotEquals(blue, preset.create().accent, preset + " should not share the default accent");
        }
    }

    @Test
    @DisplayName("a light preset keeps a light sidebar rather than a dark one")
    void lightPresetHasLightSidebar() {
        GuiTheme light = GuiThemePreset.LIGHT.create();
        assertTrue(light.sidebar.r > 150 && light.sidebar.g > 150 && light.sidebar.b > 150,
            "the light theme's rail must be light, or it would look like a hole in the window");
    }
}
