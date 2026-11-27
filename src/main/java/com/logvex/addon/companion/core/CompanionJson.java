package com.logvex.addon.companion.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON for the companion API.
 *
 * <p>HTML escaping is off so a chat message containing {@code <} or {@code &} is sent verbatim; the
 * bundled app inserts text with {@code textContent}, so there is no injection surface, and escaping
 * would only mangle what the player actually typed.
 *
 * <p>Parsing is strict about the shape and lenient about the values: a request that is not an object,
 * or a field of the wrong type, is reported rather than throwing into the request handler.
 */
public final class CompanionJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CompanionJson() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static JsonElement tree(Object value) {
        return GSON.toJsonTree(value);
    }

    /** Parses text as a JSON object, or null when it is not an object. */
    public static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads a string field, returning null when absent, null or not a primitive. */
    public static String string(JsonObject object, String name) {
        if (object == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) return null;
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads a boolean field, returning {@code fallback} when absent or not a boolean. */
    public static boolean bool(JsonObject object, String name, boolean fallback) {
        if (object == null || !object.has(name)) return fallback;
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Reads an integer field, returning {@code fallback} when absent or not a whole number.
     *
     * <p>A JSON number that arrives as {@code 3.0} is accepted as 3 because a browser's
     * {@code JSON.stringify} can produce that for an integer; {@code 3.5} is refused, since a slot
     * index or a button is never fractional and silently truncating one would click the wrong thing.
     */
    public static Integer integer(JsonObject object, String name, Integer fallback) {
        if (object == null || !object.has(name)) return fallback;
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            double value = element.getAsDouble();
            if (value != Math.rint(value)) return fallback;
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return fallback;
            return (int) value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Reads a numeric field, returning null when absent or not a finite number.
     *
     * <p>Used for degrees, where a fraction is meaningful and a missing value must not be confused
     * with zero: looking by 0 degrees is a real request, so absence has to answer null.
     */
    public static Double number(JsonObject object, String name) {
        if (object == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) return null;
        try {
            double value = element.getAsDouble();
            if (Double.isNaN(value) || Double.isInfinite(value)) return null;
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A small ordered map builder, so a response object has a stable field order. */
    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    /** Builds an {@code {"ok":..., "message":...}} body from an action result. */
    public static Map<String, Object> result(CompanionApi.ActionResult result) {
        Map<String, Object> body = map();
        body.put("ok", result.ok());
        body.put("message", result.message());
        return body;
    }
}
