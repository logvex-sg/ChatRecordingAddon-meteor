package com.logvex.addon.companion.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.logvex.addon.framework.utils.settings.SettingBridge;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a module's Meteor settings to and from the shape the companion app works with.
 *
 * <p>This is deliberately free of Minecraft and of any module lookup: it takes a {@link Settings}
 * tree and returns values or a result. That keeps the part that decides what a browser may read and
 * write testable on plain objects, and leaves the Minecraft layer with nothing to do but find the
 * module.
 *
 * <p>Reading uses {@link SettingBridge}, which round-trips every Meteor setting type through its own
 * NBT codec, so a type this class has never heard of still reports its value correctly and is
 * labelled {@code other}. Writing uses {@link Setting#set(Object)}, which runs the setting's own
 * validation: a number outside its range, or an enum name that does not exist, is refused by the
 * setting rather than quietly clamped to something the user did not ask for.
 *
 * <p>Writes are refused for a setting the module currently hides ({@link Setting#isVisible()} false).
 * A hidden setting usually belongs to a feature that is switched off, and changing it from the app
 * would edit state the in-game screen will not show, which is confusing to undo.
 */
public final class ModuleSettings {
    private ModuleSettings() {
    }

    /** Every setting of a module, in declaration order, with its value and what a control needs. */
    public static List<CompanionSnapshot.SettingView> views(Settings settings) {
        List<CompanionSnapshot.SettingView> out = new ArrayList<>();
        if (settings == null) return out;
        for (var group : settings) {
            for (Setting<?> setting : group) {
                out.add(view(group.name, setting));
            }
        }
        return out;
    }

    private static CompanionSnapshot.SettingView view(String group, Setting<?> setting) {
        Number min = null;
        Number max = null;
        List<String> options = null;
        String type;

        if (setting instanceof BoolSetting) {
            type = "bool";
        } else if (setting instanceof IntSetting integer) {
            type = "int";
            min = integer.min;
            max = integer.max;
        } else if (setting instanceof DoubleSetting decimal) {
            type = "double";
            min = decimal.min;
            max = decimal.max;
        } else if (setting instanceof EnumSetting<?> enumeration) {
            type = "enum";
            options = enumeration.getSuggestions();
        } else if (setting instanceof StringSetting) {
            type = "string";
        } else if (setting instanceof StringListSetting) {
            type = "string-list";
        } else {
            // Colour, keybind, block position, registry lists and anything else: the value still
            // round-trips, so the app can show it, but it gets no dedicated control.
            type = "other";
        }

        return new CompanionSnapshot.SettingView(
            setting.name,
            setting.title,
            setting.description,
            group,
            type,
            currentValue(setting),
            defaultValue(setting),
            min,
            max,
            options,
            isVisible(setting)
        );
    }

    /** A setting's current value as plain JSON, or null if it cannot be represented. */
    private static Object currentValue(Setting<?> setting) {
        // The simple types are read from the Java value. Reading them back through NBT would turn a
        // boolean into the byte 1, because that is how NBT stores a flag, and the app would then be
        // asked to compare a number against true.
        Object value = setting.get();
        if (value instanceof Boolean || value instanceof Number || value instanceof String) return value;
        if (value instanceof Enum<?> constant) return constant.toString();
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object item : list) {
                if (item != null) array.add(new JsonPrimitive(String.valueOf(item)));
            }
            return array;
        }
        try {
            return SettingBridge.toJson(setting);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A setting's default value as plain JSON.
     *
     * <p>Only the types the app builds a control for are reported, and they are read from the Java
     * value rather than re-serialised: the point of the default is a one-click reset, so a type
     * without a reset control gains nothing from carrying one.
     */
    private static Object defaultValue(Setting<?> setting) {
        Object value = setting.getDefaultValue();
        if (value == null) return null;
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        if (value instanceof Enum<?> constant) return constant.toString();
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object item : list) {
                if (item != null) array.add(new JsonPrimitive(String.valueOf(item)));
            }
            return array;
        }
        return null;
    }

    private static boolean isVisible(Setting<?> setting) {
        try {
            return setting.isVisible();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Applies one JSON value to one setting.
     *
     * @return what happened, in the words the app shows
     */
    public static CompanionApi.ActionResult apply(Settings settings, String settingName, JsonElement value) {
        if (settings == null) return CompanionApi.ActionResult.fail("That module has no settings.");
        if (settingName == null || settingName.isBlank()) {
            return CompanionApi.ActionResult.fail("No setting was named.");
        }
        if (value == null || value.isJsonNull()) {
            return CompanionApi.ActionResult.fail("No value was given.");
        }

        Setting<?> setting = SettingBridge.byName(settings, settingName);
        if (setting == null) {
            return CompanionApi.ActionResult.fail("No setting is called \"" + settingName + "\".");
        }
        if (!isVisible(setting)) {
            return CompanionApi.ActionResult.fail(setting.title + " is hidden while its feature is off.");
        }

        try {
            if (!write(setting, value)) {
                return CompanionApi.ActionResult.fail(
                    "Could not use " + describe(value) + " for " + setting.title + ".");
            }
        } catch (RuntimeException e) {
            return CompanionApi.ActionResult.fail(
                "Could not use " + describe(value) + " for " + setting.title + ".");
        }
        return CompanionApi.ActionResult.ok(setting.title + " set to " + readBack(setting) + ".");
    }

    /**
     * Writes one value, choosing the path that keeps the setting's own validation in charge.
     *
     * <p>Scalars go through {@link Setting#set(Object)} rather than {@link Setting#parse(String)}.
     * {@code parse} reports success for any text it can turn into a value, even when the setting then
     * refuses it — an out-of-range number leaves the old value in place but still returns true — so a
     * caller cannot tell a real write from a rejected one. {@code set} validates through the setting's
     * own {@code isValueValid} and returns what actually happened.
     *
     * <p>The JSON shape is checked against the setting's type first. That check is not redundant with
     * the setting's own: the codec path reads a value of the wrong kind as a default rather than
     * failing, so a list handed to a boolean would silently become {@code false}. Refusing the shape
     * here keeps a mismatch a refusal instead of an invented value.
     *
     * @return whether the setting accepted the value
     */
    private static boolean write(Setting<?> setting, JsonElement value) {
        if (setting instanceof BoolSetting) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return false;
            @SuppressWarnings("unchecked")
            Setting<Boolean> bool = (Setting<Boolean>) setting;
            return bool.set(value.getAsBoolean());
        }
        if (setting instanceof IntSetting) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
            @SuppressWarnings("unchecked")
            Setting<Integer> integer = (Setting<Integer>) setting;
            return integer.set(value.getAsNumber().intValue());
        }
        if (setting instanceof DoubleSetting) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
            @SuppressWarnings("unchecked")
            Setting<Double> decimal = (Setting<Double>) setting;
            return decimal.set(value.getAsNumber().doubleValue());
        }
        if (setting instanceof StringSetting) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
            @SuppressWarnings("unchecked")
            Setting<String> text = (Setting<String>) setting;
            return text.set(value.getAsString());
        }
        if (setting instanceof EnumSetting<?> enumeration) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
            return writeEnum(enumeration, value.getAsString());
        }
        if (setting instanceof StringListSetting) {
            if (!value.isJsonArray()) return false;
            List<String> items = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                if (!item.isJsonPrimitive()) return false;
                items.add(item.getAsString());
            }
            @SuppressWarnings("unchecked")
            Setting<List<String>> list = (Setting<List<String>>) setting;
            return list.set(items);
        }
        return replaceThroughCodec(setting, value);
    }

    /** Sets an enum setting from a constant name, matched without regard to case. */
    private static <T extends Enum<?>> boolean writeEnum(EnumSetting<T> setting, String name) {
        List<String> options = setting.getSuggestions();
        String needle = name.trim();
        for (String option : options) {
            if (!option.equalsIgnoreCase(needle)) continue;
            // The options are the constant names, so the constant can be found by name on the
            // setting's own default value, which is an instance of the enum in question.
            Object fallback = setting.getDefaultValue();
            if (fallback == null) return false;
            @SuppressWarnings("unchecked")
            Class<T> type = (Class<T>) fallback.getClass();
            for (T constant : type.getEnumConstants()) {
                if (constant.name().equals(option)) return setting.set(constant);
            }
        }
        return false;
    }

    /**
     * Writes a value through the setting's NBT codec, for the types with no textual form.
     *
     * <p>The codec path has no success signal of its own, so the value is compared before and after:
     * a shape the setting could not use leaves it untouched, and that is reported as a refusal
     * instead of being presented to the user as a change that did not happen.
     */
    private static boolean replaceThroughCodec(Setting<?> setting, JsonElement value) {
        String before = readJson(setting);
        try {
            SettingBridge.fromJson(setting, value);
        } catch (RuntimeException e) {
            return false;
        }
        String after = readJson(setting);
        return before == null || !before.equals(after);
    }

    /** A setting's value as a comparable string, or null when it cannot be read. */
    private static String readJson(Setting<?> setting) {
        try {
            return String.valueOf(SettingBridge.toJson(setting));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The setting's value after a write, so the app can confirm what was actually stored. */
    private static String readBack(Setting<?> setting) {
        Object value = currentValue(setting);
        return value == null ? "its new value" : String.valueOf(value);
    }

    /** A short, quoted rendering of a rejected value for the message the app shows. */
    private static String describe(JsonElement value) {
        String text = value.toString();
        return text.length() > 40 ? "\"" + text.substring(0, 37) + "...\"" : text;
    }
}
