package com.logvex.addon.framework.utils.settings;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;

import java.util.ArrayList;
import java.util.List;

/**
 * Small adapters between Meteor's {@link Settings} API and the shape the addon's config and profile
 * code expects.
 *
 * <p>Meteor exposes a settings tree as a list of {@link SettingGroup}s, each iterable over its
 * settings, and serialises a value through NBT ({@link Setting#toTag()}). The addon wants a flat
 * list of every setting, lookup by name, and JSON values. This class bridges both directions: it
 * walks the tree, and it converts a setting's NBT to and from JSON through the standard
 * {@link NbtOps}/{@link JsonOps} codecs so every Meteor setting type round-trips without a bespoke
 * serialiser per type.
 */
public final class SettingBridge {
    private SettingBridge() {
    }

    /** Every setting across every group, in declaration order. */
    public static List<Setting<?>> all(Settings settings) {
        List<Setting<?>> out = new ArrayList<>();
        for (SettingGroup group : settings) {
            for (Setting<?> setting : group) out.add(setting);
        }
        return out;
    }

    /** Every setting in one group, in declaration order. */
    public static List<Setting<?>> settings(SettingGroup group) {
        List<Setting<?>> out = new ArrayList<>();
        for (Setting<?> setting : group) out.add(setting);
        return out;
    }

    /** The setting with this name in any group, or null. */
    public static Setting<?> byName(Settings settings, String name) {
        for (SettingGroup group : settings) {
            Setting<?> setting = group.get(name);
            if (setting != null) return setting;
        }
        return null;
    }

    /** Whether a group with this name exists. */
    public static boolean hasGroup(Settings settings, String name) {
        return settings.getGroup(name) != null;
    }

    /**
     * A setting's value as JSON.
     *
     * <p>{@link Setting#toTag()} wraps the value in a compound with the setting's name and a
     * {@code value} key. Only the value element is returned, so a stored config or profile holds the
     * bare value ({@code 64}, {@code "text"}, {@code [...]}) exactly as the addon's older on-disk
     * format did — an existing config keeps loading.
     */
    public static JsonElement toJson(Setting<?> setting) {
        NbtCompound tag = setting.toTag();
        NbtElement value = tag.get("value");
        return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, value == null ? tag : value);
    }

    /** Loads a bare value previously produced by {@link #toJson}. */
    public static void fromJson(Setting<?> setting, JsonElement json) {
        if (json == null) return;
        NbtElement value = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
        NbtCompound tag = new NbtCompound();
        tag.putString("name", setting.name);
        tag.put("value", value);
        setting.fromTag(tag);
    }
}
