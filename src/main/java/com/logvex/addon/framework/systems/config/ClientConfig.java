package com.logvex.addon.framework.systems.config;

import com.logvex.addon.framework.gui.GuiThemePreset;

/** Addon-wide client settings that are not owned by any one module. */
public final class ClientConfig {
    private static final ClientConfig INSTANCE = new ClientConfig();

    /** The chat prefix that opens a command, e.g. {@code .log}. */
    private String prefix = ".";

    /** The GUI colour scheme, applied to the shared theme on load and when changed in the GUI. */
    private GuiThemePreset themePreset = GuiThemePreset.DARK;

    private ClientConfig() {
    }

    public static ClientConfig get() {
        return INSTANCE;
    }

    public String prefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) this.prefix = prefix;
    }

    public GuiThemePreset themePreset() {
        return themePreset;
    }

    public void setThemePreset(GuiThemePreset preset) {
        if (preset != null) this.themePreset = preset;
    }
}
