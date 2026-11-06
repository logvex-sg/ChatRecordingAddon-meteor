package com.logvex.addon.framework.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A list-of-strings setting, always stored as an immutable copy. */
public class StringListSetting extends Setting<List<String>> {
    private StringListSetting(String name, String description, List<String> defaultValue, Consumer<List<String>> onChanged, IVisible visible) {
        super(name, description, List.copyOf(defaultValue), onChanged, visible);
    }

    @Override
    public boolean set(List<String> newValue) {
        return super.set(newValue == null ? List.of() : List.copyOf(newValue));
    }

    public static class Builder extends SettingBuilder<Builder, List<String>> {
        public Builder() {
            super(new ArrayList<>());
        }

        /** Replaces the default with {@code values}; calling it with none clears the default. */
        public Builder defaultValue(String... values) {
            this.defaultValue = values == null ? new ArrayList<>() : new ArrayList<>(List.of(values));
            return this;
        }

        @Override
        public StringListSetting build() {
            return new StringListSetting(name, description, defaultValue, onChanged, visible);
        }
    }
}
