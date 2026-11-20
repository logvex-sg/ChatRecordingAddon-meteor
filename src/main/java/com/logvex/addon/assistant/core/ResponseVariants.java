package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reads a reply that was written with {@code |} alternatives.
 *
 * <p>Selection is deterministic: the first alternative wins, always. Random choice was removed
 * because a pipe reads to a user as a field separator, so {@code TPS {tps} | MSPT {mspt}} looked
 * like one line of two fields and silently printed one of them at random. The separator is still
 * understood so a rule stored by an older build renders its first alternative instead of a literal
 * pipe; nothing here randomises.
 *
 * <p>A blank alternative is dropped, so a trailing {@code |} cannot produce an empty reply.
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

    /**
     * The alternative to use: the first one.
     *
     * @param template the raw template, possibly with separators
     * @param random   ignored, kept so existing call sites do not change
     * @return the first alternative, or an empty string when there is none
     */
    public static String pick(String template, Random random) {
        return first(template);
    }

    /** The first alternative in {@code template}, or the whole string when it has no separator. */
    public static String first(String template) {
        List<String> parts = split(template);
        return parts.isEmpty() ? "" : parts.get(0);
    }

}
