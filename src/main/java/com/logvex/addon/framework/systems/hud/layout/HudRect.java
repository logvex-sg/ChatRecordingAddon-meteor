package com.logvex.addon.framework.systems.hud.layout;

/** An immutable pixel rectangle, used for HUD layout maths that has no Minecraft dependency. */
public record HudRect(int x, int y, int width, int height) {
    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(int px, int py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    public boolean intersects(HudRect other) {
        if (other == null) return false;
        return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
    }

    public HudRect movedTo(int nx, int ny) {
        return new HudRect(nx, ny, width, height);
    }
}
