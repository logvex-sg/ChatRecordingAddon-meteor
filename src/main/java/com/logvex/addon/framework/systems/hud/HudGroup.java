package com.logvex.addon.framework.systems.hud;

/** A named group of HUD elements. */
public class HudGroup {
    public final String name;

    public HudGroup(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
