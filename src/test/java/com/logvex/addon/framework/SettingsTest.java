package com.logvex.addon.framework;

import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.DoubleSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.Settings;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.utils.misc.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour the GUI and the config loader both depend on. */
class SettingsTest {
    private enum Mode { A, B, C }

    @Test
    @DisplayName("an integer setting clamps to its declared range")
    void intSettingClamps() {
        Setting<Integer> setting = new IntSetting.Builder()
            .name("count").defaultValue(5).min(1).max(10).build();

        setting.set(50);
        assertEquals(10, setting.get());
        setting.set(-3);
        assertEquals(1, setting.get());
        setting.set(7);
        assertEquals(7, setting.get());
    }

    @Test
    @DisplayName("a double setting accepts a default and clamps")
    void doubleSettingClamps() {
        Setting<Double> setting = new DoubleSetting.Builder()
            .name("scale").defaultValue(1.0).min(0.5).max(3.0).build();

        assertEquals(1.0, setting.get());
        setting.set(9.0);
        assertEquals(3.0, setting.get());
        setting.set(0.0);
        assertEquals(0.5, setting.get());
    }

    @Test
    @DisplayName("a string list setting stores an immutable copy")
    void stringListCopies() {
        Setting<List<String>> setting = new StringListSetting.Builder()
            .name("rules").defaultValue("a", "b").build();

        assertEquals(List.of("a", "b"), setting.get());
        setting.set(List.of("x"));
        assertEquals(List.of("x"), setting.get());
        assertThrowsUnsupported(() -> setting.get().add("y"));
    }

    private static void assertThrowsUnsupported(Runnable action) {
        boolean threw = false;
        try {
            action.run();
        } catch (UnsupportedOperationException e) {
            threw = true;
        }
        assertTrue(threw, "expected the list to be immutable");
    }

    @Test
    @DisplayName("a hidden setting reports itself invisible so the GUI can skip it")
    void visibilityIsRespected() {
        Setting<Boolean> parent = new BoolSetting.Builder().name("on").defaultValue(false).build();
        Setting<String> child = new com.logvex.addon.framework.settings.StringSetting.Builder()
            .name("detail").defaultValue("x").visible(parent::get).build();

        assertFalse(child.isVisible());
        parent.set(true);
        assertTrue(child.isVisible());
    }

    @Test
    @DisplayName("settings keep their declaration order across groups")
    void groupsPreserveOrder() {
        Settings settings = new Settings();
        SettingGroup general = settings.getDefaultGroup();
        SettingGroup advanced = settings.createGroup("Advanced");
        general.add(new BoolSetting.Builder().name("first").build());
        advanced.add(new BoolSetting.Builder().name("second").build());
        general.add(new BoolSetting.Builder().name("third").build());

        assertEquals(List.of("first", "third", "second"),
            settings.all().stream().map(s -> s.name).toList());
        assertEquals("second", settings.get("second").name);
        assertNull(settings.get("missing"));
    }

    @Test
    @DisplayName("a setting round-trips through JSON")
    void jsonRoundTrip() {
        IntSetting original = new IntSetting.Builder().name("n").defaultValue(3).min(0).max(9).build();
        original.set(7);

        IntSetting restored = new IntSetting.Builder().name("n").defaultValue(3).min(0).max(9).build();
        restored.fromJson(original.toJson());
        assertEquals(7, restored.get());
    }

    @Test
    @DisplayName("an invalid stored value is rejected and the previous value kept")
    void invalidStoredValueIsIgnored() {
        IntSetting setting = new IntSetting.Builder().name("n").defaultValue(3).min(0).max(9).build();
        setting.set(4);

        // A string where a number is expected: the parse fails and the old value survives.
        setting.fromJson(Json.write("not-a-number"));
        assertEquals(4, setting.get());
    }

    @Test
    @DisplayName("enum settings cycle through their constants")
    void enumCycles() {
        EnumSetting<Mode> setting = new EnumSetting.Builder<Mode>().name("mode").defaultValue(Mode.A).build();
        assertEquals(Mode.A, setting.get());

        Mode[] values = setting.get().getDeclaringClass().getEnumConstants();
        setting.set(values[(setting.get().ordinal() + 1) % values.length]);
        assertEquals(Mode.B, setting.get());
    }
}
