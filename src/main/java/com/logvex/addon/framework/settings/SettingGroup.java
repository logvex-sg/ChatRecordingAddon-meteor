package com.logvex.addon.framework.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A named group of settings, used purely to organise the settings UI. */
public final class SettingGroup {
    private final String name;
    private final List<Setting<?>> settings = new ArrayList<>();

    public SettingGroup(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public <T extends Setting<?>> T add(T setting) {
        settings.add(setting);
        return setting;
    }

    public List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }
}
