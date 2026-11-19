package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The macro table, and the rule for whether a typed line is a macro at all.
 *
 * <p>This is the piece that keeps macros away from everything else. A line is only a macro when it
 * begins with the configured prefix and its first word matches a known shortcut; anything else is
 * left alone and goes to the server as normal chat. The check runs on the local player's own
 * outgoing message, so a macro is never reachable from someone else's chat.
 *
 * <p>The set is immutable and rebuilt whenever the configuration changes, so a lookup never has to
 * cope with a table being edited underneath it.
 */
public final class MacroSet {
    /** The empty table: every lookup misses, and nothing is ever a macro. */
    public static final MacroSet EMPTY = new MacroSet(List.of(), "!");

    private final List<ChatMacro> macros;
    private final String prefix;

    private MacroSet(List<ChatMacro> macros, String prefix) {
        this.macros = macros == null ? List.of() : List.copyOf(macros);
        this.prefix = prefix == null || prefix.isEmpty() ? "!" : prefix;
    }

    public static MacroSet of(List<ChatMacro> macros, String prefix) {
        if (macros == null || macros.isEmpty()) return new MacroSet(List.of(), prefix);
        return new MacroSet(macros, prefix);
    }

    public List<ChatMacro> macros() {
        return macros;
    }

    public String prefix() {
        return prefix;
    }

    /** Only the macros that are switched on, in configuration order. */
    public List<ChatMacro> enabled() {
        List<ChatMacro> out = new ArrayList<>();
        for (ChatMacro macro : macros) {
            if (macro.enabled() && macro.valid()) out.add(macro);
        }
        return List.copyOf(out);
    }

    /** True when {@code message} starts with the macro prefix. */
    public boolean hasPrefix(String message) {
        return message != null && message.startsWith(prefix);
    }

    /**
     * One resolved macro call: which macro, and the text left after the shortcut.
     *
     * @param macro the macro that matched
     * @param input everything after the shortcut, trimmed of one leading space
     */
    public record Call(ChatMacro macro, String input) {
    }

    /**
     * Resolves {@code message} against the table.
     *
     * <p>Returns empty when the line is not a macro: no prefix, an unknown shortcut, or a disabled
     * macro. A disabled macro deliberately falls through to normal chat rather than silently doing
     * nothing, so the player can see that the shortcut was not recognised.
     */
    public Optional<Call> resolve(String message) {
        if (message == null) return Optional.empty();
        String text = message.strip();
        if (!text.startsWith(prefix)) return Optional.empty();

        String body = text.substring(prefix.length());
        if (body.isBlank()) return Optional.empty();

        int split = 0;
        while (split < body.length() && !Character.isWhitespace(body.charAt(split))) split++;
        String shortcut = prefix + body.substring(0, split);
        String input = body.substring(split).strip();

        for (ChatMacro macro : macros) {
            if (!macro.enabled() || !macro.valid()) continue;
            for (String trigger : macro.triggers()) {
                if (trigger.equalsIgnoreCase(shortcut)) return Optional.of(new Call(macro, input));
            }
        }
        return Optional.empty();
    }

    /**
     * True when {@code command} is this macro's own template, fully substituted.
     *
     * <p>Used as the last guard before sending: the command about to go to the server must not be
     * the shortcut itself, which would re-enter this same code and loop.
     */
    public boolean wouldRecurse(ChatMacro macro, String command) {
        if (macro == null || command == null) return false;
        String stripped = command.strip();
        for (String trigger : macro.triggers()) {
            if (stripped.equalsIgnoreCase(trigger)) return true;
        }
        return false;
    }

    /** Every shortcut and alias, for a duplicate check and for {@code !macros}. */
    public List<String> triggers() {
        List<String> out = new ArrayList<>();
        for (ChatMacro macro : macros) out.addAll(macro.triggers());
        return List.copyOf(out);
    }

    /** The spellings claimed by more than one macro, lower-cased. */
    public List<String> duplicates() {
        return ChatMacro.duplicates(macros);
    }

    /** Finds one macro by shortcut or alias, enabled or not. */
    public Optional<ChatMacro> find(String shortcut) {
        return ChatMacro.find(macros, shortcut);
    }

    /**
     * Builds a table from stored configuration lines, skipping anything unparseable.
     *
     * <p>One bad line must not take the whole feature down, so a line that will not parse is dropped
     * and the rest still work.
     */
    public static MacroSet decode(List<String> lines, String prefix) {
        List<ChatMacro> out = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                ChatMacro macro = ChatMacro.decode(line);
                if (macro != null) out.add(macro);
            }
        }
        return of(out, prefix);
    }

    /** The stored form, one line per macro. */
    public List<String> encode() {
        List<String> out = new ArrayList<>(macros.size());
        for (ChatMacro macro : macros) out.add(macro.encode());
        return List.copyOf(out);
    }

    /** The macro whose shortcut is {@code word}, ignoring the prefix, or null. */
    public ChatMacro byWord(String word) {
        if (word == null || word.isBlank()) return null;
        String wanted = word.strip().toLowerCase(Locale.ROOT);
        if (wanted.startsWith(prefix)) wanted = wanted.substring(prefix.length());
        for (ChatMacro macro : macros) {
            for (String trigger : macro.triggers()) {
                String bare = trigger.startsWith(prefix) ? trigger.substring(prefix.length()) : trigger;
                if (bare.equalsIgnoreCase(wanted)) return macro;
            }
        }
        return null;
    }
}
