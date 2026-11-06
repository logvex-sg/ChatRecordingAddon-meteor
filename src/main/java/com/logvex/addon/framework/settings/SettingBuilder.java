package com.logvex.addon.framework.settings;

import java.util.function.Consumer;

/**
 * Shared fluent builder for settings.
 *
 * <p>Every builder method returns the concrete builder type {@code B}, so a chain keeps its type
 * and {@code build()} resolves without a cast.
 */
public abstract class SettingBuilder<B, V> {
    protected String name = "undefined";
    protected String description = "";
    protected V defaultValue;
    protected IVisible visible;
    protected Consumer<V> onChanged;
    /** Whether the GUI should render this setting across the full width. */
    protected boolean wide;

    protected SettingBuilder(V defaultValue) {
        this.defaultValue = defaultValue;
    }

    @SuppressWarnings("unchecked")
    protected B self() {
        return (B) this;
    }

    public B name(String name) {
        this.name = name;
        return self();
    }

    public B description(String description) {
        this.description = description;
        return self();
    }

    public B defaultValue(V defaultValue) {
        this.defaultValue = defaultValue;
        return self();
    }

    public B visible(IVisible visible) {
        this.visible = visible;
        return self();
    }

    public B onChanged(Consumer<V> onChanged) {
        this.onChanged = onChanged;
        return self();
    }

    public B wide() {
        this.wide = true;
        return self();
    }

    public abstract Setting<V> build();
}
