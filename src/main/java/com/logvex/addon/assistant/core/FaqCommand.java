package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code !faq} command, as pure logic.
 *
 * <p>A fact is a short key/value note the assistant keeps between sessions, and an FAQ entry is
 * nothing more than a fact whose value is the answer. Treating the assistant's memory as the FAQ
 * rather than adding a second store means {@code {fact:key}} and {@code !faq view key} can never
 * disagree, and an entry added one way is visible the other way immediately.
 *
 * <p>Entries are numbered from 1, and the number is the entry's position in the store. Numbering
 * the whole store rather than each listing means a number means the same thing in every view:
 * entry 30 is entry 30 whether it was reached from a full list, a filtered list, or by typing
 * {@code !faq 30}. A per-listing count would renumber itself as soon as a filter changed, which is
 * exactly when a user is trying to read a number off the screen.
 *
 * <p>Nothing here touches the game, the clock, or the settings screen: the module owns those and
 * passes in what this needs. That keeps every rule — what a subcommand is called, what a bad key
 * does, how a long list is shortened — testable without a running client.
 */
public final class FaqCommand {

    /** The command name, without the prefix. */
    public static final String NAME = "faq";

    /**
     * The most entries the FAQ holds.
     *
     * <p>Fifty is a chat-sized corpus: it is the number a user can still page through by number,
     * and it keeps the store small enough that a listing stays readable. The cap is enforced on
     * ADD so the limit is a rule rather than a suggestion.
     */
    public static final int MAX_ENTRIES = 50;

    /** How many entries one listing shows before it is shortened. */
    public static final int DEFAULT_LIMIT = 12;

    private FaqCommand() {
    }

    /**
     * What a subcommand asks for.
     *
     * <p>{@link Kind#VIEW} also covers a bare {@code !faq <key>} and {@code !faq <number>}, because
     * those spellings are what a user reaches for first and answering them with the usage block
     * would be a small betrayal.
     */
    public enum Kind {
        /** No subcommand at all. */
        USAGE,
        LIST,
        ADD,
        REMOVE,
        VIEW,
        COUNT
    }

    /** One parsed request. {@code key} is the entry name or number, {@code value} the answer for ADD. */
    public record Request(Kind kind, String key, String value) {
        public Request {
            if (kind == null) kind = Kind.USAGE;
            if (key == null) key = "";
            if (value == null) value = "";
        }
    }

    /** One entry with the number it is addressed by. */
    public record Entry(int number, AssistantMemory.Fact fact) {
        public String key() {
            return fact.key();
        }

        public String value() {
            return fact.value();
        }
    }

    /**
     * Parses everything after {@code !faq}.
     *
     * <p>An unrecognised verb is read as a key rather than refused, so {@code !faq spawn} works as
     * well as {@code !faq view spawn}, and {@code !faq 30} opens entry 30. A user who meant a verb
     * and mistyped it still sees a "no such entry" answer that names what they typed, which points
     * at the mistake.
     */
    public static Request parse(String rest) {
        String body = rest == null ? "" : rest.strip();
        if (body.isEmpty()) return new Request(Kind.USAGE, "", "");
        String[] parts = body.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].strip() : "";
        return switch (verb) {
            case "list", "ls", "all" -> new Request(Kind.LIST, argument, "");
            case "add", "set", "remember" -> splitAdd(argument);
            case "remove", "delete", "rm", "del" -> new Request(Kind.REMOVE, argument, "");
            case "view", "get", "show", "read" -> new Request(Kind.VIEW, argument, "");
            case "count", "size" -> new Request(Kind.COUNT, "", "");
            default -> new Request(Kind.VIEW, body, "");
        };
    }

    /**
     * Splits {@code key = value}.
     *
     * <p>An ADD with no {@code =} keeps the whole argument as the key and an empty value, so the
     * caller can tell "you forgot the answer" from "you forgot the name" and say which.
     */
    private static Request splitAdd(String argument) {
        int eq = argument.indexOf('=');
        if (eq < 0) return new Request(Kind.ADD, argument.strip(), "");
        return new Request(Kind.ADD, argument.substring(0, eq).strip(),
            argument.substring(eq + 1).strip());
    }

    /** Every entry, numbered from 1 in stored order. */
    public static List<Entry> entries(AssistantMemory memory) {
        if (memory == null) return List.of();
        List<Entry> out = new ArrayList<>();
        int number = 1;
        for (AssistantMemory.Fact fact : memory.facts()) out.add(new Entry(number++, fact));
        return out;
    }

    /**
     * The entries matching {@code filter}, keeping the number each has in the full store.
     *
     * <p>An empty filter matches everything.
     */
    public static List<Entry> matching(AssistantMemory memory, String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return entries(memory);
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries(memory)) {
            if (entry.key().contains(needle)
                || entry.value().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** The entry addressed by {@code token}: a number first, then an exact key. */
    public static Optional<Entry> resolve(AssistantMemory memory, String token) {
        if (memory == null || token == null || token.isBlank()) return Optional.empty();
        String wanted = token.strip();
        int number = parseNumber(wanted);
        if (number > 0) {
            List<Entry> all = entries(memory);
            return number <= all.size() ? Optional.of(all.get(number - 1)) : Optional.empty();
        }
        return memory.fact(wanted).map(fact -> new Entry(numberOf(memory, fact), fact));
    }

    /** The number {@code fact} holds, or 0 when it is not in {@code memory}. */
    private static int numberOf(AssistantMemory memory, AssistantMemory.Fact fact) {
        for (Entry entry : entries(memory)) {
            if (entry.key().equals(fact.key())) return entry.number();
        }
        return 0;
    }

    /** The positive number {@code text} spells, or -1 when it is not a plain number. */
    public static int parseNumber(String text) {
        if (text == null) return -1;
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > 9) return -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return -1;
        }
        try {
            int value = Integer.parseInt(trimmed);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Builds the listing block.
     *
     * <p>Rows are numbered, so any of them can be read back with {@code !faq <number>}. The footer
     * states how many entries exist and how many were held back, so a shortened list never reads as
     * a complete one: a user who sees twelve rows and no footer would reasonably believe there are
     * only twelve.
     */
    public static ReplyFormat.Embed listing(List<Entry> matched, int limit, String prefix,
                                            int total) {
        List<Entry> rows = matched == null ? List.of() : matched;
        int cap = limit <= 0 ? DEFAULT_LIMIT : limit;
        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (Entry entry : rows) {
            if (fields.size() >= cap) break;
            fields.add(new ReplyFormat.Field(String.valueOf(entry.number()),
                entry.key() + " - " + entry.value()));
        }
        String p = prefix == null || prefix.isBlank() ? "!" : prefix;
        String footer;
        if (rows.size() > fields.size()) {
            footer = (rows.size() - fields.size()) + " more; " + p + NAME + " list <text> to narrow.";
        } else {
            footer = "Showing " + fields.size() + " of " + total + " entries.";
        }
        return new ReplyFormat.Embed("FAQ", fields, footer, ReplyFormat.Style.LIST);
    }

    /** The block shown for one entry. */
    public static ReplyFormat.Embed detail(Entry entry) {
        return new ReplyFormat.Embed("FAQ #" + entry.number(), List.of(
            new ReplyFormat.Field("Question", entry.key()),
            new ReplyFormat.Field("Answer", entry.value())
        ), "", ReplyFormat.Style.BOX);
    }

    /** The block shown for {@code !faq count}. */
    public static ReplyFormat.Embed count(int entries, String prefix) {
        int size = Math.max(0, entries);
        String p = prefix == null || prefix.isBlank() ? "!" : prefix;
        return new ReplyFormat.Embed("FAQ", List.of(
            new ReplyFormat.Field("Used", size + " of " + MAX_ENTRIES),
            new ReplyFormat.Field("Spare", String.valueOf(Math.max(0, MAX_ENTRIES - size))),
            new ReplyFormat.Field("Read", p + NAME + " <number>")
        ), "", ReplyFormat.Style.PLAIN);
    }

    /** The usage block, one subcommand per line. */
    public static ReplyFormat.Embed usage(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "!" : prefix;
        return new ReplyFormat.Embed("FAQ", List.of(
            new ReplyFormat.Field(p + NAME + " list [text]", "Show entries, numbered."),
            new ReplyFormat.Field(p + NAME + " <number>", "Read entry 1-" + MAX_ENTRIES + "."),
            new ReplyFormat.Field(p + NAME + " add key = answer", "Add or update."),
            new ReplyFormat.Field(p + NAME + " remove <number|key>", "Delete an entry."),
            new ReplyFormat.Field(p + NAME + " count", "How many of " + MAX_ENTRIES + " are used.")
        ), "", ReplyFormat.Style.LIST);
    }

    /**
     * The reason an ADD was refused, or an empty string when it can go ahead.
     *
     * <p>{@code stored} is the current number of entries. Updating an existing key is always
     * allowed: it does not grow the store, so a full FAQ can still be corrected.
     */
    public static String addProblem(Request request, int stored, boolean replaces) {
        if (request.key().isBlank()) return "Give the entry a name: " + NAME + " add <key> = <answer>";
        if (request.value().isBlank()) return "Give the entry an answer after the =";
        if (!replaces && stored >= MAX_ENTRIES) {
            return "The FAQ is full (" + MAX_ENTRIES + " entries). Remove one with " + NAME
                + " remove <number> first.";
        }
        return "";
    }

    /** True when adding {@code key} would replace an existing entry rather than add a new one. */
    public static boolean replaces(AssistantMemory memory, String key) {
        return memory != null && memory.fact(key).isPresent();
    }
}
