package com.logvex.addon.framework.systems.config;

/** Addon-wide client settings that are not owned by any one module. */
public final class ClientConfig {
    private static final ClientConfig INSTANCE = new ClientConfig();

    /** The chat prefix that opens a command, e.g. {@code .log}. */
    private String prefix = ".";

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
}
