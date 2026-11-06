package com.logvex.addon.framework.settings;

/** A free-text setting. */
public class StringSetting extends Setting<String> {
    private StringSetting(String name, String description, String defaultValue, java.util.function.Consumer<String> onChanged, IVisible visible) {
        super(name, description, defaultValue, onChanged, visible);
    }

    @Override
    public boolean set(String newValue) {
        return super.set(newValue == null ? "" : newValue);
    }

    public static class Builder extends SettingBuilder<Builder, String> {
        public Builder() {
            super("");
        }

        @Override
        public StringSetting build() {
            return new StringSetting(name, description, defaultValue, onChanged, visible);
        }
    }
}
