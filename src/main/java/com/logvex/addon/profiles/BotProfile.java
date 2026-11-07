package com.logvex.addon.profiles;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named snapshot of a set of settings, keyed by setting name.
 *
 * <p>The snapshot stores the raw JSON value of each setting rather than a typed field, so a profile
 * only needs to know the setting names. That keeps the profile format stable as settings are added
 * or removed: a value whose name no longer exists is simply ignored on apply, and a setting absent
 * from a profile keeps its current value. Nothing here touches Minecraft, so profiles are unit
 * testable.
 */
public final class BotProfile {
    private final String name;
    private final boolean builtin;
    private final Map<String, JsonElement> values = new LinkedHashMap<>();

    public BotProfile(String name) {
        this(name, false);
    }

    public BotProfile(String name, boolean builtin) {
        this.name = name == null ? "" : name.trim();
        this.builtin = builtin;
    }

    public String name() {
        return name;
    }

    /** True for a shipped default profile, which cannot be renamed or deleted. */
    public boolean builtin() {
        return builtin;
    }

    public Map<String, JsonElement> values() {
        return values;
    }

    public BotProfile put(String settingName, JsonElement value) {
        if (settingName != null && value != null) values.put(settingName, value);
        return this;
    }

    public JsonElement get(String settingName) {
        return values.get(settingName);
    }

    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return (builtin ? "* " : "") + name + " (" + values.size() + " settings)";
    }
}
