package com.logvex.addon.framework;

import com.logvex.addon.framework.utils.settings.SettingBridge;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringListSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour the config loader, the profiles and the GUI all depend on, over Meteor's settings model
 * and the {@link SettingBridge} the addon layers on top of it.
 */
class SettingsTest {

    @Test
    @DisplayName("a setting keeps the value it is set to and reports the change")
    void setAndWasChanged() {
        Setting<Integer> setting = new IntSetting.Builder()
            .name("count").defaultValue(5).min(1).max(10).build();

        assertFalse(setting.wasChanged());
        setting.set(7);
        assertEquals(7, setting.get());
        assertTrue(setting.wasChanged());

        setting.reset();
        assertEquals(5, setting.get());
        assertFalse(setting.wasChanged(), "reset must clear the changed flag");
    }

    @Test
    @DisplayName("the bridge lists every setting across every group, in order")
    void bridgeListsEverySetting() {
        Settings settings = new Settings();
        SettingGroup general = settings.getDefaultGroup();
        SettingGroup advanced = settings.createGroup("Advanced");
        general.add(new BoolSetting.Builder().name("first").build());
        advanced.add(new BoolSetting.Builder().name("second").build());
        general.add(new BoolSetting.Builder().name("third").build());

        assertEquals(List.of("first", "third", "second"),
            SettingBridge.all(settings).stream().map(s -> s.name).toList());
        assertEquals(List.of("second"),
            SettingBridge.settings(advanced).stream().map(s -> s.name).toList());
    }

    @Test
    @DisplayName("the bridge finds a setting by name and reports group presence")
    void bridgeLookup() {
        Settings settings = new Settings();
        settings.getDefaultGroup().add(new IntSetting.Builder().name("radius").defaultValue(3).build());

        assertNotNull(SettingBridge.byName(settings, "radius"));
        assertNull(SettingBridge.byName(settings, "missing"));
        assertTrue(SettingBridge.hasGroup(settings, "General"));
        assertFalse(SettingBridge.hasGroup(settings, "No Such Group"));
    }

    @Test
    @DisplayName("a value round-trips through the bridge as a bare value")
    void bridgeRoundTripsBareValue() {
        Setting<Integer> original = new IntSetting.Builder().name("n").defaultValue(3).min(0).max(9).build();
        original.set(7);

        // The stored form must be the bare value, not a wrapping compound, so an existing config
        // written by an earlier build still loads.
        assertEquals(7, SettingBridge.toJson(original).getAsInt());

        Setting<Integer> restored = new IntSetting.Builder().name("n").defaultValue(3).min(0).max(9).build();
        SettingBridge.fromJson(restored, SettingBridge.toJson(original));
        assertEquals(7, restored.get());
    }

    @Test
    @DisplayName("a string list round-trips through the bridge")
    void bridgeRoundTripsList() {
        Setting<List<String>> setting = new StringListSetting.Builder()
            .name("rules").defaultValue("a", "b").build();
        setting.set(List.of("x", "y"));

        Setting<List<String>> restored = new StringListSetting.Builder()
            .name("rules").defaultValue("a", "b").build();
        SettingBridge.fromJson(restored, SettingBridge.toJson(setting));
        assertEquals(List.of("x", "y"), restored.get());
    }
}
