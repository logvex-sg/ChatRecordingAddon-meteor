package com.logvex.addon.framework.settings;

/** An integer setting with an optional inclusive range. */
public class IntSetting extends Setting<Integer> {
    public final int min;
    public final int max;

    private IntSetting(String name, String description, Integer defaultValue, int min, int max,
                       java.util.function.Consumer<Integer> onChanged, IVisible visible) {
        super(name, description, defaultValue, onChanged, visible);
        this.min = min;
        this.max = max;
        this.value = clamp(defaultValue);
    }

    private int clamp(int v) {
        if (min != Integer.MIN_VALUE && v < min) return min;
        if (max != Integer.MAX_VALUE && v > max) return max;
        return v;
    }

    @Override
    public boolean set(Integer newValue) {
        return newValue != null && super.set(clamp(newValue));
    }

    @Override
    protected boolean isValueValid(Integer newValue) {
        return (min == Integer.MIN_VALUE || newValue >= min) && (max == Integer.MAX_VALUE || newValue <= max);
    }

    public static class Builder extends SettingBuilder<Builder, Integer> {
        private int min = Integer.MIN_VALUE;
        private int max = Integer.MAX_VALUE;

        public Builder() {
            super(0);
        }

        public Builder min(int min) {
            this.min = min;
            return this;
        }

        public Builder max(int max) {
            this.max = max;
            return this;
        }

        public Builder sliderRange(int min, int max) {
            this.min = min;
            this.max = max;
            return this;
        }

        @Override
        public IntSetting build() {
            return new IntSetting(name, description, defaultValue, min, max, onChanged, visible);
        }
    }
}
