package com.logvex.addon.framework.utils.render.color;

import java.util.Objects;

/** A mutable RGB colour with a 0-255 component range. */
public class Color {
    public int r;
    public int g;
    public int b;
    /** Alpha, 0-255. Plain colours are opaque; {@link SettingColor} carries a user-chosen alpha. */
    public int a;

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public Color(int r, int g, int b, int a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    /** Returns a copy so callers cannot mutate shared palette colours. */
    public Color copy() {
        return new Color(r, g, b, a);
    }

    @Override
    public String toString() {
        return "Color(" + r + ", " + g + ", " + b + ", " + a + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color color)) return false;
        return r == color.r && g == color.g && b == color.b;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r, g, b);
    }
}
