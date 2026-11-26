package com.logvex.addon.chatlog.core;

import java.util.Locale;

/**
 * Resolves which webhook a given kind of card should go to.
 *
 * <p>One shared URL is fine until the user wants help pages in a different channel from reports, or
 * macro runs kept out of the public chat archive. Each feature can therefore name its own URL; when
 * it does not, the shared relay URL is used, so a user who never touches this sees no change.
 *
 * <p>An override is a full URL rather than a name, because Discord webhooks have no identity to
 * refer to. A blank override means "inherit", which is the default for every feature.
 */
public final class FeatureWebhook {
    /** The features that can be routed separately. */
    public enum Feature {
        MACROS("macros"),
        MODCALL("modcall"),
        HELP("help"),
        STATUS("status"),
        /**
         * Keyword alerts.
         *
         * <p>The noisiest sender: one busy word can fire many cards an hour, so this is the route
         * most users want out of the main channel.
         */
        KEYWORDS("keywords");

        private final String id;

        Feature(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** The feature for {@code id}, or null when it is not one of these. */
        public static Feature of(String id) {
            if (id == null) return null;
            String wanted = id.strip().toLowerCase(Locale.ROOT);
            for (Feature feature : values()) {
                if (feature.id.equals(wanted)) return feature;
            }
            return null;
        }
    }

    private final String fallback;

    /** One override per feature, as {@code feature=url} lines. */
    private final java.util.Map<Feature, String> overrides;

    public FeatureWebhook(String fallback, java.util.List<String> overrides) {
        this.fallback = fallback == null ? "" : fallback.strip();
        this.overrides = parse(overrides);
    }

    /**
     * The URL to use for {@code feature}.
     *
     * <p>Falls back to the shared URL, and returns blank when neither is set, so a caller can test
     * one value instead of two.
     */
    public String url(Feature feature) {
        if (feature != null) {
            String own = overrides.get(feature);
            if (own != null && !own.isBlank()) return own;
        }
        return fallback;
    }

    /** True when {@code feature} has its own URL rather than inheriting the shared one. */
    public boolean overridden(Feature feature) {
        String own = feature == null ? null : overrides.get(feature);
        return own != null && !own.isBlank();
    }

    /** True when anything would be sent for {@code feature}. */
    public boolean configured(Feature feature) {
        return !url(feature).isBlank();
    }

    /** The shared URL, which every unset feature inherits. */
    public String fallback() {
        return fallback;
    }

    /**
     * Reads {@code feature=url} lines.
     *
     * <p>An unknown feature name is ignored rather than treated as an error: the setting is a plain
     * text list, and a typo should leave the shared URL in place instead of breaking delivery.
     */
    static java.util.Map<Feature, String> parse(java.util.List<String> lines) {
        java.util.Map<Feature, String> out = new java.util.EnumMap<>(Feature.class);
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null) continue;
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            int eq = text.indexOf('=');
            if (eq <= 0) continue;
            Feature feature = Feature.of(text.substring(0, eq));
            String url = text.substring(eq + 1).strip();
            if (feature != null && !url.isBlank()) out.put(feature, url);
        }
        return out;
    }
}