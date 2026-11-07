package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.LogAddon;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns every registered HUD element.
 *
 * <p>One instance of each element kind is created lazily and kept for the lifetime of the addon, so an
 * element's settings survive being hidden and a settings screen always edits the live instance rather
 * than a throwaway copy. Only active elements are ticked and drawn, so a hidden element costs nothing
 * per frame.
 *
 * <p>The screen size is read once per frame from Minecraft and handed to every element so it can
 * resolve its anchor; that keeps the layout maths testable in isolation and out of the render loop.
 * Elements that overlap are drawn with the dragged one last, so a drag is always visible on top.
 */
public final class Hud implements Iterable<HudElement> {
    private static final Hud INSTANCE = new Hud();

    private final Map<String, HudElementInfo<?>> infos = new LinkedHashMap<>();
    private final Map<HudElementInfo<?>, HudElement> instances = new LinkedHashMap<>();
    private final List<HudElement> active = new ArrayList<>();

    private double textScale = 1;
    private int screenWidth = 1920;
    private int screenHeight = 1080;

    private Hud() {
    }

    public static Hud get() {
        return INSTANCE;
    }

    public void register(HudElementInfo<?> info) {
        if (info != null) infos.putIfAbsent(info.name, info);
    }

    public HudElementInfo<?> info(String name) {
        return infos.get(name);
    }

    /** Every registered element kind, in registration order. */
    public Collection<HudElementInfo<?>> infos() {
        return List.copyOf(infos.values());
    }

    /** The live instance of an element kind, created on first use. */
    public HudElement instance(HudElementInfo<?> info) {
        if (info == null) return null;
        HudElement element = instances.get(info);
        if (element == null) {
            element = info.create();
            instances.put(info, element);
        }
        return element;
    }

    public boolean isActive(HudElementInfo<?> info) {
        HudElement element = info == null ? null : instances.get(info);
        return element != null && active.contains(element);
    }

    /** Adds an element of the given kind, or returns the existing one. */
    public HudElement add(HudElementInfo<?> info, int x, int y) {
        HudElement element = instance(info);
        if (element == null) return null;
        if (!active.contains(element)) {
            element.setPos(x, y);
            active.add(element);
            element.setActive(true);
        }
        return element;
    }

    /** Re-activates an element that is already known, keeping its position. */
    public void add(HudElement element) {
        if (element == null || active.contains(element)) return;
        active.add(element);
        element.setActive(true);
    }

    public void remove(HudElement element) {
        if (element == null) return;
        active.remove(element);
        element.setActive(false);
    }

    public void clear() {
        for (HudElement element : new ArrayList<>(active)) remove(element);
    }

    /** The elements currently shown, in activation order. */
    public List<HudElement> elements() {
        return List.copyOf(active);
    }

    /** Every element that has ever been instantiated, active or not. */
    public List<HudElement> allInstances() {
        return List.copyOf(instances.values());
    }

    /** Shows the element kind when hidden, hides it when shown. */
    public void toggle(HudElementInfo<?> info) {
        if (info == null) return;
        if (isActive(info)) remove(instance(info));
        else add(info, 4, 4);
    }

    @Override
    public java.util.Iterator<HudElement> iterator() {
        return elements().iterator();
    }

    public double textScale() {
        return textScale;
    }

    public void setTextScale(double textScale) {
        this.textScale = textScale <= 0 ? 1 : textScale;
    }

    /** The current HUD surface size, refreshed from the window each frame. */
    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    /** Refreshes the cached screen size from the game window. */
    public void updateScreenSize() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        var window = mc.getWindow();
        if (window != null) {
            this.screenWidth = Math.max(1, window.getScaledWidth());
            this.screenHeight = Math.max(1, window.getScaledHeight());
        }
    }

    /** Per-frame tick and render of every active element, on the render thread. */
    public void render(HudRenderer renderer) {
        updateScreenSize();

        List<HudElement> order = new ArrayList<>(active);
        // Draw a dragged element last so it is never hidden behind a neighbour.
        order.sort((a, b) -> Boolean.compare(a.isDragging(), b.isDragging()));

        for (HudElement element : order) {
            try {
                element.reposition(screenWidth, screenHeight);
                element.tick(renderer);
                element.reposition(screenWidth, screenHeight);
                element.render(renderer);
            } catch (RuntimeException e) {
                LogAddon.LOG.error("HUD element {} failed to render", element.info.name, e);
            }
        }
    }

    /**
     * Applies a chosen anchor to an element by converting its current absolute position into the
     * matching offset, so switching anchor from the editor does not jump the element across the
     * screen.
     */
    public void applyAnchor(HudElement element, com.logvex.addon.framework.systems.hud.layout.HudAnchor anchor) {
        if (element == null || anchor == null) return;
        updateScreenSize();
        element.setAnchor(anchor);
        element.setPos(element.getX(), element.getY());
    }
}
