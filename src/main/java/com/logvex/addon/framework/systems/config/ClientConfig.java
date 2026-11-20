package com.logvex.addon.framework.systems.config;

import com.logvex.addon.framework.gui.GuiThemePreset;

/** LogClientContext-wide client settings that are not owned by any one module. */
public final class ClientConfig {
    private static final ClientConfig INSTANCE = new ClientConfig();

    /**
     * The chat prefix that opens one of this addon's own commands, e.g. {@code !log}.
     *
     * <p>Only the addon's commands honour it: a line is intercepted when it starts with this prefix
     * and the next word is a command this addon registered. Meteor's own commands keep using
     * Meteor's prefix, so the two never compete for the same line.
     */
    private String prefix = "!";

    /** The GUI colour scheme, applied to the shared theme on load and when changed in the GUI. */
    private GuiThemePreset themePreset = GuiThemePreset.BLUE;

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
