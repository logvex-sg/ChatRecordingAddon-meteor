package com.logvex.addon.framework.settings;

/** A double setting with an optional inclusive range. */
public class DoubleSetting extends Setting<Double> {
    public final double min;
    public final double max;

    private DoubleSetting(String name, String description, Double defaultValue, double min, double max,
                          java.util.function.Consumer<Double> onChanged, IVisible visible) {
        super(name, description, defaultValue, onChanged, visible);
        this.min = min;
        this.max = max;
        this.value = clamp(defaultValue);
    }

    private double clamp(double v) {
        if (min != -Double.MAX_VALUE && v < min) return min;
        if (max != Double.MAX_VALUE && v > max) return max;
        return v;
    }

    @Override
    public boolean set(Double newValue) {
        return newValue != null && super.set(clamp(newValue));
    }

    @Override
    protected boolean isValueValid(Double newValue) {
        return (min == -Double.MAX_VALUE || newValue >= min) && (max == Double.MAX_VALUE || newValue <= max);
    }

    public static class Builder extends SettingBuilder<Builder, Double> {
        private double min = -Double.MAX_VALUE;
        private double max = Double.MAX_VALUE;

        public Builder() {
            super(0.0);
        }

        public Builder defaultValue(double value) {
            this.defaultValue = value;
            return this;
        }

        public Builder min(double min) {
            this.min = min;
            return this;
        }

        public Builder max(double max) {
            this.max = max;
            return this;
        }

        public Builder sliderRange(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        @Override
        public DoubleSetting build() {
            return new DoubleSetting(name, description, defaultValue, min, max, onChanged, visible);
        }
    }
}
