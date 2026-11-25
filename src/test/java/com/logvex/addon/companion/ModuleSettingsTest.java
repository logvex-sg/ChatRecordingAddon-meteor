package com.logvex.addon.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.logvex.addon.companion.core.CompanionApi;
import com.logvex.addon.companion.core.CompanionSnapshot;
import com.logvex.addon.companion.core.ModuleSettings;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translation between a module's Meteor settings and what the companion app reads and writes.
 *
 * <p>These use real {@link Setting} instances rather than a stand-in, so the validation under test is
 * Meteor's own: a value is refused because the setting refuses it, not because a fake said so.
 */
class ModuleSettingsTest {

    private enum Mode { FAST, SLOW }

    /** A module's worth of settings: one of each type the app builds a control for. */
    private Settings settings() {
        Settings settings = new Settings();
        SettingGroup group = settings.getDefaultGroup();
        group.add(new BoolSetting.Builder().name("enabled").description("Whether it runs.")
            .defaultValue(true).build());
        group.add(new IntSetting.Builder().name("count").description("How many.")
            .defaultValue(8).min(1).max(64).sliderMin(1).sliderMax(64).build());
        group.add(new DoubleSetting.Builder().name("speed").description("How fast.")
            .defaultValue(1.5).min(0).max(10).sliderMin(0).sliderMax(10).build());
        group.add(new EnumSetting.Builder<Mode>().name("mode").description("Which mode.")
            .defaultValue(Mode.FAST).build());
        group.add(new StringSetting.Builder().name("label").description("A label.")
            .defaultValue("hello").build());
        group.add(new StringListSetting.Builder().name("names").description("Names.")
            .defaultValue(List.of("a", "b")).build());
        return settings;
    }

    private static CompanionSnapshot.SettingView view(List<CompanionSnapshot.SettingView> views, String name) {
        return views.stream().filter(v -> v.name().equals(name)).findFirst().orElse(null);
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    // ------------------------------------------------------------------ reading

    @Test
    @DisplayName("every setting is reported with its type, value and what a control needs")
    void reportsTypesAndValues() {
        List<CompanionSnapshot.SettingView> views = ModuleSettings.views(settings());

        assertEquals(6, views.size(), "every setting is reported, not a subset");
        assertEquals("bool", view(views, "enabled").type());
        assertEquals("int", view(views, "count").type());
        assertEquals("double", view(views, "speed").type());
        assertEquals("enum", view(views, "mode").type());
        assertEquals("string", view(views, "label").type());
        assertEquals("string-list", view(views, "names").type());

        assertEquals(true, view(views, "enabled").value());
        assertEquals(8, ((Number) view(views, "count").value()).intValue());
        assertEquals(1.5, ((Number) view(views, "speed").value()).doubleValue());
        assertEquals("FAST", String.valueOf(view(views, "mode").value()));
        assertEquals("hello", view(views, "label").value());
        assertEquals(2, ((JsonArray) view(views, "names").value()).size());
    }

    @Test
    @DisplayName("a number reports its bounds and an enum reports its options, so the app can build a control")
    void reportsBoundsAndOptions() {
        List<CompanionSnapshot.SettingView> views = ModuleSettings.views(settings());

        assertEquals(1, view(views, "count").min().intValue());
        assertEquals(64, view(views, "count").max().intValue());
        assertEquals(0.0, view(views, "speed").min().doubleValue());
        assertEquals(10.0, view(views, "speed").max().doubleValue());
        assertEquals(List.of("FAST", "SLOW"), view(views, "mode").options());
        assertNull(view(views, "label").min(), "a text setting has no bounds");
    }

    @Test
    @DisplayName("a default is reported so the app can offer a reset, and a group name is carried")
    void reportsDefaultsAndGroups() {
        List<CompanionSnapshot.SettingView> views = ModuleSettings.views(settings());

        assertEquals(8, ((Number) view(views, "count").defaultValue()).intValue());
        assertEquals("FAST", view(views, "mode").defaultValue());
        assertNotNull(view(views, "count").group(), "a setting knows which group it belongs to");
        assertTrue(view(views, "count").visible());
    }

    @Test
    @DisplayName("an empty or absent settings tree reports nothing rather than throwing")
    void emptySettingsAreSafe() {
        assertTrue(ModuleSettings.views(new Settings()).isEmpty());
        assertTrue(ModuleSettings.views(null).isEmpty());
    }

    // ------------------------------------------------------------------ writing

    @Test
    @DisplayName("a valid value of each type is applied to the real setting")
    void appliesValidValues() {
        Settings settings = settings();

        assertTrue(ModuleSettings.apply(settings, "enabled", json("false")).ok());
        assertEquals(false, settings.get("enabled").get());

        assertTrue(ModuleSettings.apply(settings, "count", json("32")).ok());
        assertEquals(32, settings.get("count").get());

        assertTrue(ModuleSettings.apply(settings, "speed", json("2.5")).ok());
        assertEquals(2.5, settings.get("speed").get());

        assertTrue(ModuleSettings.apply(settings, "mode", json("\"SLOW\"")).ok());
        assertEquals(Mode.SLOW, settings.get("mode").get());

        assertTrue(ModuleSettings.apply(settings, "label", json("\"changed\"")).ok());
        assertEquals("changed", settings.get("label").get());

        assertTrue(ModuleSettings.apply(settings, "names", json("[\"x\",\"y\",\"z\"]")).ok());
        assertEquals(List.of("x", "y", "z"), settings.get("names").get());
    }

    @Test
    @DisplayName("a number outside its range is refused and the old value is kept")
    void refusesOutOfRangeNumber() {
        Settings settings = settings();

        CompanionApi.ActionResult result = ModuleSettings.apply(settings, "count", json("999"));

        assertFalse(result.ok(), "999 is above the setting's maximum");
        assertEquals(8, settings.get("count").get(), "a refused write must not change the value");
    }

    @Test
    @DisplayName("an enum name that does not exist is refused rather than guessed")
    void refusesUnknownEnumName() {
        Settings settings = settings();

        CompanionApi.ActionResult result = ModuleSettings.apply(settings, "mode", json("\"TURBO\""));

        assertFalse(result.ok());
        assertEquals(Mode.FAST, settings.get("mode").get());
    }

    @Test
    @DisplayName("a value of the wrong shape is refused, not silently coerced")
    void refusesWrongShape() {
        Settings settings = settings();

        assertFalse(ModuleSettings.apply(settings, "count", json("\"not a number\"")).ok());
        assertFalse(ModuleSettings.apply(settings, "label", json("{}")).ok());
        assertEquals(8, settings.get("count").get());
        assertEquals("hello", settings.get("label").get());
    }

    @Test
    @DisplayName("an unknown setting name is refused with a message naming it")
    void refusesUnknownSetting() {
        CompanionApi.ActionResult result = ModuleSettings.apply(settings(), "nope", json("1"));

        assertFalse(result.ok());
        assertTrue(result.message().contains("nope"), "the message must say what was not found: " + result.message());
    }

    @Test
    @DisplayName("a missing value is refused before anything is touched")
    void refusesMissingValue() {
        Settings settings = settings();

        assertFalse(ModuleSettings.apply(settings, "count", null).ok());
        assertEquals(8, settings.get("count").get());
    }

    @Test
    @DisplayName("a hidden setting is refused, so the app cannot edit what the module is not using")
    void refusesHiddenSetting() {
        Settings settings = new Settings();
        Setting<Boolean> gate = new BoolSetting.Builder().name("gate").defaultValue(false).build();
        settings.getDefaultGroup().add(gate);
        settings.getDefaultGroup().add(new IntSetting.Builder().name("only-when-on")
            .defaultValue(5).min(0).max(10).visible(() -> gate.get()).build());

        assertFalse(ModuleSettings.views(settings).stream()
            .filter(v -> v.name().equals("only-when-on")).findFirst().orElseThrow().visible(),
            "the view must report the setting as hidden while the gate is off");
        assertFalse(ModuleSettings.apply(settings, "only-when-on", json("7")).ok());
        assertEquals(5, settings.get("only-when-on").get());

        // Turn the gate on: the same setting is now editable.
        assertTrue(ModuleSettings.apply(settings, "gate", json("true")).ok());
        assertTrue(ModuleSettings.apply(settings, "only-when-on", json("7")).ok());
        assertEquals(7, settings.get("only-when-on").get());
    }

    @Test
    @DisplayName("a successful write says what the value became, in plain language")
    void reportsWhatWasStored() {
        CompanionApi.ActionResult result = ModuleSettings.apply(settings(), "count", json("16"));

        assertTrue(result.ok());
        assertTrue(result.message().contains("16"), "the message should confirm the new value: " + result.message());
    }

    @Test
    @DisplayName("a setting whose type has no dedicated control still round-trips through its codec")
    void otherTypesStillRoundTrip() {
        Settings settings = new Settings();
        settings.getDefaultGroup().add(new BoolSetting.Builder().name("flag").defaultValue(true).build());

        // A list handed to a bool is refused: the codec cannot use it and the value is unchanged.
        CompanionApi.ActionResult rejected = ModuleSettings.apply(settings, "flag", new JsonArray());
        assertFalse(rejected.ok(), "a bool must not accept a list");
        assertEquals(true, settings.get("flag").get());

        assertTrue(ModuleSettings.apply(settings, "flag", new JsonPrimitive(false)).ok());
        assertEquals(false, settings.get("flag").get());
    }
}
