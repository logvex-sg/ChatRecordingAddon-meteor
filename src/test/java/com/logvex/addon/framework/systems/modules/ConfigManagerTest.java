package com.logvex.addon.framework.systems.modules;

import com.google.gson.JsonObject;
import com.logvex.addon.framework.systems.config.ConfigManager;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringListSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings half of the config: a module's or HUD element's settings must serialise to the same
 * bare-value JSON the addon has always written, and must load back over a live settings tree. These
 * exercise the real serialisation methods against Meteor's settings model, without needing a running
 * client.
 */
class ConfigManagerTest {

    @SuppressWarnings("unchecked")
    private static Setting<List<String>> names(Settings settings) {
        return (Setting<List<String>>) settings.get("names");
    }

    @SuppressWarnings("unchecked")
    private static Setting<Boolean> enabled(Settings settings) {
        return (Setting<Boolean>) settings.get("enabled");
    }

    @SuppressWarnings("unchecked")
    private static Setting<Integer> limit(Settings settings) {
        return (Setting<Integer>) settings.get("limit");
    }

    private static Settings sample() {
        Settings settings = new Settings();
        settings.getDefaultGroup().add(new BoolSetting.Builder().name("enabled").defaultValue(false).build());
        settings.getDefaultGroup().add(new IntSetting.Builder()
            .name("limit").defaultValue(5).min(0).max(100).build());
        settings.getDefaultGroup().add(new StringListSetting.Builder()
            .name("names").defaultValue("a", "b").build());
        return settings;
    }

    @Test
    @DisplayName("settings round-trip through the config serialiser")
    void settingsRoundTrip() {
        Settings source = sample();
        enabled(source).set(true);
        limit(source).set(42);
        names(source).set(List.of("x", "y", "z"));

        JsonObject entry = new JsonObject();
        entry.add("settings", ConfigManager.settingsToJson(source));

        Settings restored = sample();
        ConfigManager.applySettings(restored, entry);

        assertEquals(Boolean.TRUE, (Boolean) (Object) restored.get("enabled").get());
        assertEquals(42, ((Number) restored.get("limit").get()).intValue());
        assertEquals(List.of("x", "y", "z"), restored.get("names").get());
    }

    @Test
    @DisplayName("a value stored by an older build as a bare value still loads")
    void loadsLegacyBareValues() {
        // An old config wrote the bare value ("limit": 9), not a wrapping object.
        JsonObject entry = new JsonObject();
        JsonObject values = new JsonObject();
        values.addProperty("limit", 9);
        entry.add("settings", values);

        Settings restored = sample();
        ConfigManager.applySettings(restored, entry);

        assertEquals(9, ((Number) restored.get("limit").get()).intValue());
    }

    @Test
    @DisplayName("an entry with no settings object leaves the defaults untouched")
    void missingSettingsLeavesDefaults() {
        Settings restored = sample();
        ConfigManager.applySettings(restored, new JsonObject());
        assertEquals(5, ((Number) restored.get("limit").get()).intValue());
        assertEquals(Boolean.FALSE, (Boolean) (Object) restored.get("enabled").get());
    }

    @Test
    @DisplayName("a setting this build does not know is ignored, not fatal")
    void unknownSettingIsIgnored() {
        JsonObject entry = new JsonObject();
        JsonObject values = new JsonObject();
        values.addProperty("removed-setting", 1);
        values.addProperty("limit", 12);
        entry.add("settings", values);

        Settings restored = sample();
        ConfigManager.applySettings(restored, entry);

        assertEquals(12, ((Number) restored.get("limit").get()).intValue(), "a known setting must still load");
    }

    @Test
    @DisplayName("serialised output holds the bare value for each setting")
    void serialisesBareValues() {
        Settings source = sample();
        limit(source).set(7);
        JsonObject json = ConfigManager.settingsToJson(source);

        assertTrue(json.has("limit"));
        assertEquals(7, json.get("limit").getAsInt());
        assertEquals("a", json.getAsJsonArray("names").get(0).getAsString());
    }
}
