package com.logvex.addon.framework.utils.render;

import com.logvex.addon.framework.utils.render.color.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour maths behind every rounded panel, glow and gradient.
 *
 * <p>These are pure functions, so they are tested directly rather than through a drawn frame. A wrong
 * blend or a misplaced alpha is invisible in a unit test but shows up as a washed-out or opaque
 * interface, which is exactly the kind of bug this file is here to catch.
 */
class Render2DTest {

    @Test
    @DisplayName("argb packs a colour and treats a zero alpha as opaque")
    void packsColour() {
        Color colour = new Color(10, 20, 30, 0);
        assertEquals(0xFF0A141E, Render2D.argb(colour), "a zero alpha must be read as fully opaque");
        assertEquals(0x800A141E, Render2D.argb(colour, 0x80), "an explicit alpha must override the colour's");
    }

    @Test
    @DisplayName("blend returns the endpoints at 0 and 1 and the midpoint in between")
    void blendEndpoints() {
        int from = 0xFF000000;
        int to = 0xFFFFFFFF;
        assertEquals(from, Render2D.blend(from, to, 0));
        assertEquals(to, Render2D.blend(from, to, 1));
        assertEquals(0xFF808080, Render2D.blend(from, to, 0.5));
    }

    @Test
    @DisplayName("blend clamps out-of-range t rather than producing a wild colour")
    void blendClamps() {
        assertEquals(0xFF000000, Render2D.blend(0xFF000000, 0xFFFFFFFF, -5));
        assertEquals(0xFFFFFFFF, Render2D.blend(0xFF000000, 0xFFFFFFFF, 5));
    }

    @Test
    @DisplayName("withAlpha scales only the alpha channel and clamps the factor")
    void withAlphaScales() {
        int colour = 0x80FF0000;
        assertEquals(0x40FF0000, Render2D.withAlpha(colour, 0.5), "half of 0x80 is 0x40");
        assertEquals(0x00FF0000, Render2D.withAlpha(colour, 0), "a zero factor must be fully transparent");
        assertEquals(0x80FF0000, Render2D.withAlpha(colour, 5), "a factor above 1 must not brighten");
        // The RGB channels must never be touched by an alpha operation.
        assertEquals(0xFF0000, Render2D.withAlpha(colour, 0.5) & 0x00FFFFFF);
    }

    @Test
    @DisplayName("alphaOf replaces the alpha without disturbing the colour")
    void alphaOfReplaces() {
        assertEquals(0x3300FF00, Render2D.alphaOf(0xFF00FF00, 0x33));
    }

    @Test
    @DisplayName("argb(r,g,b,a) clamps each component to a byte")
    void componentPackingClamps() {
        assertEquals(0xFFFFFFFF, Render2D.argb(300, 300, 300, 300));
        assertEquals(0x00000000, Render2D.argb(-5, -5, -5, -5));
        assertEquals(0x00000000, Render2D.argb(-1, -1, -1, 0));
    }

    @Test
    @DisplayName("a blend between a colour and itself is that colour")
    void blendIdempotent() {
        int colour = 0xAB123456;
        assertEquals(colour, Render2D.blend(colour, colour, 0.37));
    }

    @Test
    @DisplayName("blend moves each channel monotonically from source to target")
    void blendMonotonic() {
        int from = Render2D.argb(0, 0, 0, 255);
        int to = Render2D.argb(255, 255, 255, 255);
        int previous = -1;
        for (int i = 0; i <= 10; i++) {
            int value = (Render2D.blend(from, to, i / 10.0) >>> 16) & 0xFF;
            assertTrue(value >= previous, "the red channel must not go backwards as t rises");
            previous = value;
        }
    }
}
