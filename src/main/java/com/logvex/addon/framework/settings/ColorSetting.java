package com.logvex.addon.framework.settings;

import com.logvex.addon.framework.utils.render.color.SettingColor;

/** An RGBA colour setting. */
public class ColorSetting extends Setting<SettingColor> {
    private ColorSetting(String name, String description, SettingColor defaultValue, java.util.function.Consumer<SettingColor> onChanged, IVisible visible) {
        super(name, description, defaultValue, onChanged, visible);
    }

    @Override
    public boolean set(SettingColor newValue) {
        return super.set(newValue == null ? null : newValue.copy());
    }

    public static class Builder extends SettingBuilder<Builder, SettingColor> {
        public Builder() {
            super(new SettingColor(255, 255, 255));
        }

        @Override
        public ColorSetting build() {
            return new ColorSetting(name, description, defaultValue, onChanged, visible);
        }
    }
}
