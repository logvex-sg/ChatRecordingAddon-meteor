package com.logvex.addon.framework.systems.hud;

import java.util.function.Supplier;

/** Describes one kind of HUD element and how to create it. */
public class HudElementInfo<T extends HudElement> {
    public final HudGroup group;
    public final String name;
    public final String title;
    public final String description;
    public final Supplier<T> factory;

    public HudElementInfo(HudGroup group, String name, String title, String description, Supplier<T> factory) {
        this.group = group;
        this.name = name;
        this.title = title;
        this.description = description;
        this.factory = factory;
    }

    public HudElementInfo(HudGroup group, String name, String description, Supplier<T> factory) {
        this(group, name, name, description, factory);
    }

    public T create() {
        return factory.get();
    }
}
