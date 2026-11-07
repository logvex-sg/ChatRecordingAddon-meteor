package com.logvex.addon.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WCheckbox;
import com.logvex.addon.framework.gui.widgets.containers.WCard;
import com.logvex.addon.framework.gui.widgets.WSlider;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.DoubleSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.settings.StringSetting;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the GUI control for a {@link Setting}.
 *
 * <p>Keeping this in one place is what makes every module's settings look and behave the same: a
 * boolean is always a checkbox, a number is always a slider, an enum always cycles on click. Any
 * edit marks the config dirty so the periodic saver persists it.
 */
public final class SettingsWidgets {
    private SettingsWidgets() {
    }

    /** A labelled row for one setting, or {@code null} when the setting should be hidden. */
    public static WWidget row(GuiTheme theme, Setting<?> setting) {
        if (!setting.isVisible()) return null;

        WCard card = theme.card();
        WVerticalList column = theme.verticalList();

        column.add(theme.label(setting.name)).expandX();
        if (setting.description != null && !setting.description.isBlank()) {
            column.add(theme.label(setting.description).color(theme.muted)).expandX();
        }

        WWidget control = control(theme, setting);
        if (control != null) column.add(control).expandX();
        card.add(column).expandX();
        return card;
    }

    @SuppressWarnings("unchecked")
    private static WWidget control(GuiTheme theme, Setting<?> setting) {
        if (setting instanceof BoolSetting bool) {
            return new WCheckbox("", bool::get, value -> {
                bool.set(value);
                markDirty();
            });
        }
        if (setting instanceof IntSetting integer) {
            int min = integer.min == Integer.MIN_VALUE ? 0 : integer.min;
            int max = integer.max == Integer.MAX_VALUE ? Math.max(min + 100, min) : integer.max;
            WSlider slider = new WSlider(min, max, true,
                () -> integer.get().doubleValue(),
                value -> {
                    integer.set((int) Math.round(value));
                    markDirty();
                });
            slider.theme = theme;
            return slider;
        }
        if (setting instanceof DoubleSetting decimal) {
            double min = decimal.min == -Double.MAX_VALUE ? 0 : decimal.min;
            double max = decimal.max == Double.MAX_VALUE ? Math.max(min + 1, min) : decimal.max;
            WSlider slider = new WSlider(min, max, false,
                decimal::get,
                value -> {
                    decimal.set(value);
                    markDirty();
                });
            slider.theme = theme;
            return slider;
        }
        if (setting instanceof EnumSetting<?> enumSetting) {
            return enumButton(theme, enumSetting);
        }
        if (setting instanceof StringSetting string) {
            WTextBox box = theme.textBox(string.get());
            box.action = () -> {
                string.set(box.get());
                markDirty();
            };
            return box;
        }
        if (setting instanceof StringListSetting list) {
            WTextBox box = theme.textBox(String.join(", ", list.get()));
            box.action = () -> {
                List<String> values = new ArrayList<>();
                for (String part : box.get().split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) values.add(trimmed);
                }
                list.set(values);
                markDirty();
            };
            return box;
        }
        if (setting instanceof ColorSetting color) {
            return colorControls(theme, color);
        }
        return null;
    }

    private static WWidget enumButton(GuiTheme theme, EnumSetting<?> setting) {
        WButton button = theme.button(enumLabel(setting));
        button.action = () -> {
            Object[] values = setting.get().getDeclaringClass().getEnumConstants();
            int next = (setting.get().ordinal() + 1) % values.length;
            cycle(setting, values[next]);
            button.set(enumLabel(setting));
            markDirty();
        };
        return button;
    }

    /** Sets an enum setting through a raw call, since its type parameter is not known here. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cycle(EnumSetting<?> setting, Object value) {
        ((EnumSetting) setting).set((Enum) value);
    }

    private static String enumLabel(EnumSetting<?> setting) {
        Enum<?> value = setting.get();
        return value == null ? "-" : value.name();
    }

    private static WWidget colorControls(GuiTheme theme, ColorSetting setting) {
        WHorizontalList row = theme.horizontalList();
        row.add(channel(theme, "R", setting, 0)).expandX();
        row.add(channel(theme, "G", setting, 1)).expandX();
        row.add(channel(theme, "B", setting, 2)).expandX();
        return row;
    }

    private static WWidget channel(GuiTheme theme, String name, ColorSetting setting, int index) {
        WVerticalList column = theme.verticalList();
        column.add(theme.label(name)).expandX();
        WSlider slider = new WSlider(0, 255, true,
            () -> switch (index) {
                case 0 -> setting.get().r;
                case 1 -> setting.get().g;
                default -> setting.get().b;
            },
            value -> {
                SettingColor current = setting.get();
                int v = (int) Math.round(value);
                SettingColor next = new SettingColor(
                    index == 0 ? v : current.r,
                    index == 1 ? v : current.g,
                    index == 2 ? v : current.b,
                    current.a);
                setting.set(next);
                markDirty();
            });
        slider.theme = theme;
        column.add(slider).expandX();
        return column;
    }

    /** Every row for a list of settings, skipping hidden ones. */
    public static List<WWidget> rows(GuiTheme theme, List<Setting<?>> settings) {
        List<WWidget> rows = new ArrayList<>();
        for (Setting<?> setting : settings) {
            WWidget row = row(theme, setting);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    private static void markDirty() {
        ConfigManager.get().markDirty();
    }
}
