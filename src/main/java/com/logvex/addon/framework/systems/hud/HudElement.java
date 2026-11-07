package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.settings.Settings;
import com.logvex.addon.framework.systems.hud.layout.HudAnchor;
import com.logvex.addon.framework.systems.hud.layout.HudLayout;
import com.logvex.addon.framework.systems.hud.layout.HudRect;

/**
 * A single HUD element.
 *
 * <p>Elements are created once and kept in the {@link Hud}; enabling one adds it, disabling removes
 * it. {@link #tick} runs every frame before {@link #render} so an element can size itself to its
 * current content, and both are skipped while the element is inactive.
 *
 * <p>Position is stored as an {@link HudAnchor} plus a pixel offset rather than an absolute pixel, so
 * a centred element stays centred and a right-anchored element keeps its distance from the edge when
 * the window or GUI scale changes. The absolute rectangle is recomputed from the screen size each
 * frame. All elements share the {@link HudSettings.Shared} controls (Appearance and Position groups),
 * which is what makes the editor able to treat every element uniformly.
 */
public abstract class HudElement {
    public final HudElementInfo<?> info;
    public final Settings settings = new Settings();
    /** Shared Appearance and Position settings, installed in the constructor of every subclass. */
    protected final HudSettings.Shared shared;

    /** Absolute top-left, recomputed from the anchor each frame. */
    public int x;
    public int y;

    private boolean active;
    private double width;
    private double height;
    /** Set while a drag is in progress so the element is drawn above its neighbours. */
    private boolean dragging;

    protected HudElement(HudElementInfo<?> info) {
        this.info = info;
        this.shared = HudSettings.install(settings);
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

    /**
     * Places the element at an absolute pixel position, converting it to the stored anchor offset.
     * The screen size is read from Minecraft, so this is a client-thread operation.
     */
    public void setPos(int absX, int absY) {
        int screenWidth = Hud.get().screenWidth();
        int screenHeight = Hud.get().screenHeight();
        int[] offset = HudLayout.offsetFor(anchor(), absX, absY, getWidth(), getHeight(), screenWidth, screenHeight, margin());
        shared.offsetX.set(offset[0]);
        shared.offsetY.set(offset[1]);
        this.x = absX;
        this.y = absY;
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

    public HudAnchor anchor() {
        HudAnchor value = shared.anchor.get();
        return value == null ? HudAnchor.TOP_LEFT : value;
    }

    public void setAnchor(HudAnchor anchor) {
        if (anchor != null) shared.anchor.set(anchor);
    }

    public int offsetX() {
        return shared.offsetX.get();
    }

    public int offsetY() {
        return shared.offsetY.get();
    }

    /** Sets the stored anchor offset directly, used when restoring a saved layout. */
    public void setOffset(int offsetX, int offsetY) {
        shared.offsetX.set(offsetX);
        shared.offsetY.set(offsetY);
    }

    protected int margin() {
        return HudLayout.DEFAULT_MARGIN;
    }

    /** Absolute bounds for the current frame. */
    public HudRect bounds() {
        return new HudRect(x, y, Math.max(1, getWidth()), Math.max(1, getHeight()));
    }

    /** Recomputes the absolute position from the anchor and offset; called before tick/render. */
    public void reposition(int screenWidth, int screenHeight) {
        HudRect rect = HudLayout.resolve(anchor(), offsetX(), offsetY(), getWidth(), getHeight(),
            screenWidth, screenHeight, margin());
        this.x = rect.x();
        this.y = rect.y();
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setDragging(boolean dragging) {
        this.dragging = dragging;
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

    /** Persisted state: the anchor plus its offset. Absolute pixels are never stored, so a layout
     * survives a window resize or a GUI-scale change. */
    public Object[] toState() {
        return new Object[]{anchor().name(), offsetX(), offsetY()};
    }

    /** Restores a saved anchor and offset, ignoring anything malformed. */
    public void fromState(Object[] state) {
        if (state == null) return;
        if (state.length >= 1) setAnchor(HudAnchor.parse(String.valueOf(state[0]), anchor()));
        if (state.length >= 2 && state[1] instanceof Number n) shared.offsetX.set(n.intValue());
        if (state.length >= 3 && state[2] instanceof Number n) shared.offsetY.set(n.intValue());
    }
}
