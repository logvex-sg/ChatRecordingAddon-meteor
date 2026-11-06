package com.logvex.addon.framework.settings;

/** A boolean setting. */
public class BoolSetting extends Setting<Boolean> {
    private BoolSetting(String name, String description, Boolean defaultValue, java.util.function.Consumer<Boolean> onChanged, IVisible visible) {
        super(name, description, defaultValue, onChanged, visible);
    }

    public static class Builder extends SettingBuilder<Builder, Boolean> {
        public Builder() {
            super(false);
        }

        @Override
        public BoolSetting build() {
            return new BoolSetting(name, description, defaultValue, onChanged, visible);
        }
    }
}
