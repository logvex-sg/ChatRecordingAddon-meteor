package com.logvex.addon.framework.utils.render.color;

import java.util.Objects;

/** An RGBA colour used by {@code ColorSetting}. Alpha is 0-255. */
public class SettingColor extends Color {
    public SettingColor(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public SettingColor(int r, int g, int b, int a) {
        super(r, g, b, a);
    }

    public SettingColor(SettingColor other) {
        this(other.r, other.g, other.b, other.a);
    }

    public SettingColor copy() {
        return new SettingColor(r, g, b, a);
    }

    @Override
    public String toString() {
        return "SettingColor(" + r + ", " + g + ", " + b + ", " + a + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettingColor c)) return false;
        return r == c.r && g == c.g && b == c.b && a == c.a;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r, g, b, a);
    }
}
