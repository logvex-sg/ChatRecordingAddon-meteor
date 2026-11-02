package com.logvex.addon.chatrelay.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Thin Gson wrapper. Gson ships with Minecraft, so it needs no extra dependency. */
public final class Json {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }

    public static Map<?, ?> toMap(String json) {
        JsonElement element = parse(json);
        if (!element.isJsonObject()) return null;
        return GSON.fromJson(element, Map.class);
    }

    public static List<String> toStringList(String json) {
        List<String> out = new ArrayList<>();
        JsonElement element = parse(json);
        if (element == null || !element.isJsonArray()) return out;
        for (JsonElement e : element.getAsJsonArray()) {
            out.add(e.isJsonNull() ? "" : e.getAsString());
        }
        return out;
    }
}
