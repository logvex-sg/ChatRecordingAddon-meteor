package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Chooses one reply from a rule's alternatives.
 *
 * <p>Two ways to vary a reply, and they are deliberately different things:
 * <ul>
 *   <li><strong>Variants</strong> ({@code reply=}) — several full templates separated by
 *       {@code |}. One is picked at random per reply, so the same trigger does not read the same
 *       way twice.</li>
 *   <li><strong>Pools</strong> ({@code pool=}) — extra lines appended as a second sentence, used for
 *       the optional random sign-off.</li>
 * </ul>
 *
 * <p>Selection is injectable ({@link #pick}) so a test can pin the outcome; the live path uses a
 * shared {@link Random}. A blank alternative is dropped, so a trailing {@code |} cannot produce an
 * empty reply.
 */
public final class ResponseVariants {
    /** Separates alternatives inside one template string. */
    public static final String VARIANT_SEPARATOR = "|";

    private ResponseVariants() {
    }

    /**
     * Splits {@code template} into its alternatives, dropping blanks.
     *
     * <p>A template with no separator yields a single-element list, so the common case costs nothing.
     */
    public static List<String> split(String template) {
        if (template == null || template.isBlank()) return List.of();
        if (!template.contains(VARIANT_SEPARATOR)) {
            return List.of(template.trim());
        }
        List<String> out = new ArrayList<>(4);
        for (String part : template.split("\\" + VARIANT_SEPARATOR)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }

    /** The distinct alternatives in {@code template}, order preserved. */
    public static List<String> distinct(String template) {
        List<String> parts = split(template);
        if (parts.size() < 2) return parts;
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) seen.add(part);
        return List.copyOf(seen);
    }

    /** How many alternatives {@code template} offers. */
    public static int count(String template) {
        return split(template).size();
    }

    /**
     * Picks one alternative.
     *
     * @param template the raw template, possibly with separators
     * @param random   the source of randomness; null uses the shared instance
     * @return the chosen alternative, or an empty string when there is none
     */
    public static String pick(String template, Random random) {
        List<String> parts = split(template);
        if (parts.isEmpty()) return "";
        if (parts.size() == 1) return parts.get(0);
        Random source = random == null ? SharedRandom.get() : random;
        return parts.get(source.nextInt(parts.size()));
    }

    /**
     * Joins a base reply with one line from {@code pool}.
     *
     * <p>The pool is a separate setting so a user can add flavour without rewriting the reply, and
     * so turning it off restores the plain reply exactly.
     */
    public static String withPool(String reply, String pool, Random random) {
        if (pool == null || pool.isBlank()) return reply;
        String extra = pick(pool, random);
        if (extra.isEmpty()) return reply;
        if (reply == null || reply.isBlank()) return extra;
        return reply.endsWith(".") || reply.endsWith("!") || reply.endsWith("?")
            ? reply + " " + extra
            : reply + ". " + extra;
    }

    /** A shared {@link Random}, so every call site does not allocate its own. */
    static final class SharedRandom {
        private static final Random INSTANCE = new Random();

        private SharedRandom() {
        }

        static Random get() {
            return INSTANCE;
        }
    }

    /** Normalises a pool setting to lower-case tokens for the greeting matcher. */
    static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return List.copyOf(out);
    }
}
