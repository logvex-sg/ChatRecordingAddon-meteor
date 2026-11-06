package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.settings.Settings;

/**
 * A single HUD element.
 *
 * <p>Elements are created once and kept in the {@link Hud}; enabling one adds it, disabling removes
 * it. {@link #tick} runs every frame before {@link #render} so an element can size itself to its
 * current content, and both are skipped while the element is inactive. Position is stored in screen
 * pixels so it survives a resize.
 */
public abstract class HudElement {
    public final HudElementInfo<?> info;
    public final Settings settings = new Settings();

    public int x;
    public int y;

    private boolean active;
    private double width;
    private double height;

    protected HudElement(HudElementInfo<?> info) {
        this.info = info;
    }

    public boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public void setSize(double width, double height) {
        this.width = width;
        this.height = height;
    }

    public void setPos(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return (int) Math.ceil(width);
    }

    public int getHeight() {
        return (int) Math.ceil(height);
    }

    /** Shows or hides the element, keeping its position. */
    public void toggle() {
        if (isActive()) Hud.get().remove(this);
        else Hud.get().add(this);
    }

    public void remove() {
        Hud.get().remove(this);
    }

    /** Sizes the element for the current frame. */
    public void tick(HudRenderer renderer) {
    }

    /** Draws the element. */
    public void render(HudRenderer renderer) {
    }

    /** Persisted position, so a restart keeps the element where the user put it. */
    public int[] toState() {
        return new int[]{x, y};
    }

    public void fromState(int[] state) {
        if (state != null && state.length == 2) {
            setPos(state[0], state[1]);
        }
    }
}
