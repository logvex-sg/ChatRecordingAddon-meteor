package com.logvex.addon.framework.settings;

/** A setting constrained to the constants of an enum. */
public class EnumSetting<T extends Enum<T>> extends Setting<T> {
    private EnumSetting(String name, String description, T defaultValue, java.util.function.Consumer<T> onChanged, IVisible visible) {
        super(name, description, defaultValue, onChanged, visible);
    }

    public static class Builder<T extends Enum<T>> extends SettingBuilder<Builder<T>, T> {
        public Builder() {
            super(null);
        }

        @Override
        public EnumSetting<T> build() {
            return new EnumSetting<>(name, description, defaultValue, onChanged, visible);
        }
    }
}
