package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single Discord rich embed.
 *
 * <p>The webhook output used to be one flat {@code content} string, which reads as a wall of text and
 * is truncated at 2000 characters. An embed instead gives the reader a coloured card with a title,
 * a description and up to 25 name/value fields, so a keyword hit or a chat summary looks like a
 * purpose-built message rather than a paste.
 *
 * <p>The builder enforces Discord's documented limits as values are added, so a busy server can never
 * produce a payload the API rejects. It is Minecraft-free and serialises to a plain map, so the exact
 * shape can be asserted in a unit test without a running client.
 */
public final class DiscordEmbed {
    /** Maximum length of the embed title. */
    public static final int TITLE_LIMIT = 256;
    /** Maximum length of the embed description. */
    public static final int DESCRIPTION_LIMIT = 4096;
    /** Maximum length of a field name. */
    public static final int FIELD_NAME_LIMIT = 256;
    /** Maximum length of a field value. */
    public static final int FIELD_VALUE_LIMIT = 1024;
    /** Maximum number of fields in one embed. */
    public static final int MAX_FIELDS = 25;
    /** Maximum length of the footer text. */
    public static final int FOOTER_LIMIT = 2048;
    /** Maximum length of the author name. */
    public static final int AUTHOR_LIMIT = 256;
    /** Discord's combined character budget across the whole embed. */
    public static final int TOTAL_LIMIT = 6000;

    /** One embed field. {@code inline} controls whether Discord packs it beside its neighbour. */
    public record Field(String name, String value, boolean inline) {
        public Field {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
        }
    }

    private String title = "";
    private String description = "";
    private String url = "";
    private String colorHex = "";
    private String footer = "";
    private String timestamp = "";
    private String authorName = "";
    private String authorIconUrl = "";
    private final List<Field> fields = new ArrayList<>();

    /** Sets the title, truncated to the limit. */
    public DiscordEmbed title(String value) {
        this.title = clip(value, TITLE_LIMIT);
        return this;
    }

    /** Sets the description, truncated to the limit. */
    public DiscordEmbed description(String value) {
        this.description = clip(value, DESCRIPTION_LIMIT);
        return this;
    }

    /** Sets the clickable title URL. Ignored by Discord when the title is empty. */
    public DiscordEmbed url(String value) {
        this.url = value == null ? "" : value.trim();
        return this;
    }

    /** Sets the left-hand accent colour from a 24-bit RGB value. */
    public DiscordEmbed color(int rgb) {
        this.colorHex = EmbedColor.toHex(rgb);
        return this;
    }

    /** Sets the footer text, truncated to the limit. */
    public DiscordEmbed footer(String value) {
        this.footer = clip(value, FOOTER_LIMIT);
        return this;
    }

    /** Sets the ISO-8601 timestamp shown under the footer. */
    public DiscordEmbed timestamp(String iso8601) {
        this.timestamp = iso8601 == null ? "" : iso8601;
        return this;
    }

    /** Sets the author line and its optional icon. */
    public DiscordEmbed author(String name, String iconUrl) {
        this.authorName = clip(name, AUTHOR_LIMIT);
        this.authorIconUrl = iconUrl == null ? "" : iconUrl.trim();
        return this;
    }

    /**
     * Adds a field. A blank name or value is skipped (Discord rejects an empty field), and the field
     * is dropped once {@link #MAX_FIELDS} is reached, so callers can keep appending without checking.
     */
    public DiscordEmbed field(String name, String value, boolean inline) {
        if (name == null || name.isBlank() || value == null || value.isBlank()) return this;
        if (fields.size() >= MAX_FIELDS) return this;
        fields.add(new Field(clip(name, FIELD_NAME_LIMIT), clip(value, FIELD_VALUE_LIMIT), inline));
        return this;
    }

    /** Adds a non-inline field. */
    public DiscordEmbed field(String name, String value) {
        return field(name, value, false);
    }

    public boolean isEmpty() {
        return title.isBlank() && description.isBlank() && fields.isEmpty();
    }

    public List<Field> fields() {
        return List.copyOf(fields);
    }

    /** The serialised embed as an ordered map, ready to place in a webhook payload. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!title.isBlank()) map.put("title", title);
        if (!url.isBlank() && !title.isBlank()) map.put("url", url);
        if (!description.isBlank()) map.put("description", description);
        if (!colorHex.isBlank()) map.put("color", EmbedColor.parse(colorHex, EmbedColor.BLURPLE));
        if (!authorName.isBlank()) {
            Map<String, Object> author = new LinkedHashMap<>();
            author.put("name", authorName);
            if (!authorIconUrl.isBlank()) author.put("icon_url", authorIconUrl);
            map.put("author", author);
        }
        if (!fields.isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>(fields.size());
            for (Field field : fields) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", field.name());
                f.put("value", field.value());
                f.put("inline", field.inline());
                out.add(f);
            }
            map.put("fields", out);
        }
        if (!footer.isBlank()) {
            Map<String, Object> footerMap = new LinkedHashMap<>();
            footerMap.put("text", footer);
            map.put("footer", footerMap);
        }
        if (!timestamp.isBlank()) map.put("timestamp", timestamp);
        return map;
    }

    /** The serialised embed as a JSON string. */
    public String toJson() {
        return Json.toJson(toMap());
    }

    /** Total visible characters, for the {@link #TOTAL_LIMIT} budget. */
    public int length() {
        int total = title.length() + description.length() + footer.length() + authorName.length();
        for (Field field : fields) total += field.name().length() + field.value().length();
        return total;
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String text = value.strip();
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)) + "\u2026";
    }
}
