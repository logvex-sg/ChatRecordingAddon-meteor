package com.logvex.addon.framework.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The ordered set of {@link SettingGroup}s belonging to one module or HUD element. */
public final class Settings {
    private final SettingGroup defaultGroup = new SettingGroup("General");
    private final List<SettingGroup> groups = new ArrayList<>(List.of(defaultGroup));

    public SettingGroup getDefaultGroup() {
        return defaultGroup;
    }

    public SettingGroup createGroup(String name) {
        SettingGroup group = new SettingGroup(name);
        groups.add(group);
        return group;
    }

    /** Creates a group at a fixed position, so shared groups can precede an owner's own ones. */
    public SettingGroup createGroupAt(int index, String name) {
        SettingGroup group = new SettingGroup(name);
        groups.add(Math.max(0, Math.min(index, groups.size())), group);
        return group;
    }

    /** True when a group with this name already exists, so a shared group is never duplicated. */
    public boolean hasGroup(String name) {
        for (SettingGroup group : groups) {
            if (group.name().equals(name)) return true;
        }
        return false;
    }

    public List<SettingGroup> groups() {
        return Collections.unmodifiableList(groups);
    }

    /** Every setting across every group, in declaration order. */
    public List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        for (SettingGroup group : groups) all.addAll(group.settings());
        return all;
    }

    /** Looks a setting up by name, for code that has only the name at hand. */
    public Setting<?> get(String name) {
        return byName(name);
    }

    public Setting<?> byName(String name) {
        for (Setting<?> setting : all()) {
            if (setting.name.equals(name)) return setting;
        }
        return null;
    }
}
