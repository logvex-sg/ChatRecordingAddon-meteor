package com.logvex.addon.framework.utils.misc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.util.List;

/** Thin Gson wrapper so settings and module state share one serialisation policy. */
public final class Json {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Json() {
    }

    public static JsonElement write(Object value) {
        return GSON.toJsonTree(value);
    }

    /** Parses JSON text as {@code type}, returning null when the text is malformed. */
    @SuppressWarnings("unchecked")
    public static <T> T parse(String text, Class<?> type) {
        if (text == null || text.isBlank()) return null;
        try {
            return (T) GSON.fromJson(text, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads {@code json} as {@code type}, tolerating a shape that no longer matches. */
    @SuppressWarnings("unchecked")
    public static <T> T read(Object raw, Class<?> type) {
        JsonElement json = raw instanceof JsonElement element ? element : write(raw);
        if (json == null || json.isJsonNull()) return null;
        try {
            if (List.class.isAssignableFrom(type)) {
                return (T) GSON.fromJson(json, List.class);
            }
            return (T) GSON.fromJson(json, type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
