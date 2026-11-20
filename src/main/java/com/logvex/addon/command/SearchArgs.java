package com.logvex.addon.command;

import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.MessageType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the argument line of the archive search command into a {@link LogQuery}.
 *
 * <p>A user types one line, but the archive understands several independent filters. Rather than
 * invent a second command per filter, a single line accepts {@code key:value} terms alongside plain
 * words: {@code log search griefing player:alice type:chat}. Everything not claimed by a known key
 * stays free text, so the common case — searching for a phrase — needs no syntax at all.
 *
 * <p>Kept apart from {@code LogCommand} for the same reason as {@link TaskArgs}: this touches only
 * Minecraft-free types, so the parsing rules can be tested without booting Minecraft.
 */
final class SearchArgs {

    /** The filters a single search line resolved to. */
    record Parsed(LogQuery query, int limit) {
    }

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private SearchArgs() {
    }

    /**
     * Parses {@code raw} into a query.
     *
     * <p>Unknown {@code key:value} terms are treated as ordinary text rather than rejected: a chat
     * message can legitimately contain a colon, and refusing the whole search because of one would
     * be worse than searching for it literally.
     */
    static Parsed parse(String raw) {
        LogQuery.Builder builder = LogQuery.builder();
        StringBuilder text = new StringBuilder();
        int limit = DEFAULT_LIMIT;

        for (String term : split(raw)) {
            int colon = term.indexOf(':');
            if (colon > 0) {
                String key = term.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = term.substring(colon + 1);
                if (!value.isEmpty()) {
                    switch (key) {
                        case "player", "from" -> {
                            builder.player(value);
                            continue;
                        }
                        case "type" -> {
                            Set<MessageType> types = types(value);
                            if (!types.isEmpty()) {
                                builder.types(types);
                                continue;
                            }
                        }
                        case "limit", "n" -> {
                            int parsed = limit(value);
                            if (parsed > 0) {
                                limit = parsed;
                                continue;
                            }
                        }
                        default -> {
                            // Not a key we know; fall through and search for it as text.
                        }
                    }
                }
            }
            if (text.length() > 0) text.append(' ');
            text.append(term);
        }

        if (text.length() > 0) builder.text(text.toString());
        return new Parsed(builder.build(), limit);
    }

    /** Splits on whitespace, keeping a double-quoted phrase together so it stays one term. */
    private static java.util.List<String> split(String raw) {
        java.util.List<String> terms = new java.util.ArrayList<>();
        if (raw == null) return terms;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    terms.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) terms.add(current.toString());
        return terms;
    }

    /**
     * Resolves a comma-separated type list, accepting the ids the rest of the addon uses.
     *
     * <p>An unrecognised name is dropped rather than mapped to {@code OTHER}: filtering for a type
     * that does not exist should return nothing, not quietly return every uncategorised message.
     */
    static Set<MessageType> types(String value) {
        Set<MessageType> out = EnumSet.noneOf(MessageType.class);
        if (value == null) return out;
        for (String part : value.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) continue;
            MessageType type = MessageType.fromString(name);
            if (type != MessageType.OTHER || name.equalsIgnoreCase("other")) out.add(type);
        }
        return out;
    }

    private static int limit(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed <= 0 ? -1 : Math.min(parsed, MAX_LIMIT);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The accepted filter keys, for the usage line. */
    static String usage() {
        return "player:<name> type:<chat|system|join|leave|death> limit:<1-" + MAX_LIMIT + ">";
    }
}
