package com.logvex.addon.framework.settings;

import com.google.gson.JsonElement;
import com.logvex.addon.framework.utils.misc.Json;

import java.util.function.Consumer;

/**
 * A single configurable value on a module or HUD element.
 *
 * <p>Values are stored as JSON through {@link Json}, so a setting round-trips across restarts
 * without each type needing its own serialiser. A setting never throws out of {@link #set}: an
 * invalid value is rejected and the previous one is kept.
 */
public class Setting<T> {
    public final String name;
    public final String title;
    public final String description;

    protected final T defaultValue;
    protected T value;
    protected final Consumer<T> onChanged;
    protected final IVisible visible;

    public Setting(String name, String description, T defaultValue, Consumer<T> onChanged, IVisible visible) {
        this.name = name;
        this.title = name;
        this.description = description == null ? "" : description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.onChanged = onChanged;
        this.visible = visible;
    }

    public T get() {
        return value;
    }

    /** Sets the value when valid, notifying the change listener. Returns whether it was accepted. */
    public boolean set(T newValue) {
        if (newValue != null && !isValueValid(newValue)) return false;
        boolean changed = !java.util.Objects.equals(this.value, newValue);
        this.value = newValue;
        if (changed && onChanged != null) onChanged.accept(newValue);
        return true;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public void reset() {
        set(defaultValue);
    }

    public boolean wasChanged() {
        return !java.util.Objects.equals(value, defaultValue);
    }

    public boolean isVisible() {
        return visible == null || visible.isVisible();
    }

    protected boolean isValueValid(T newValue) {
        return true;
    }

    // -------------------------------------------------------------- persistence

    public JsonElement toJson() {
        return Json.write(value);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(Object json) {
        T parsed = (T) Json.read(json, defaultValue == null ? Object.class : defaultValue.getClass());
        if (parsed != null) set(parsed);
    }

    @Override
    public String toString() {
        return value == null ? "null" : value.toString();
    }
}
