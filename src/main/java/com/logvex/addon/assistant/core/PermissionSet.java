package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The whole permission model: the global switch, the administrator list, and one rule per command.
 *
 * <p>The single entry point is {@link #permits}. Everything else is bookkeeping. That matters
 * because the check has to happen in exactly one place: if a caller could reach a command through a
 * second path, that path would need its own copy of the rules and the two would eventually disagree.
 *
 * <p>Three failure modes are handled by refusing rather than allowing:
 * <ul>
 *   <li>An unreadable rule falls back to {@link CommandPermission#DEFAULT_LEVEL}, not to open.</li>
 *   <li>A command with no rule at all is treated as {@code EVERYONE} only when the global switch is
 *       off; with permissions enabled, an unlisted command keeps its conservative default.</li>
 *   <li>An administrator-only command is never satisfiable by a chat sender when no administrators
 *       are configured — an empty list must not mean "everyone".</li>
 * </ul>
 *
 * <p>Aliases are resolved to one canonical name before the check ({@link #canonical}), so a second
 * spelling of a restricted command cannot slip past the rule that governs it.
 */
public final class PermissionSet {
    /** The outcome of one check, with a reason a human can act on. */
    public record Verdict(boolean allowed, String reason) {
        public static final Verdict ALLOWED = new Verdict(true, "");

        public static Verdict denied(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** How strictly an unlisted command is treated. */
    public enum DefaultPolicy {
        /** A command with no rule is open to everyone. The permissive default. */
        OPEN,
        /** A command with no rule is local-only. The safe default. */
        CLOSED;

        public static DefaultPolicy parse(String value, DefaultPolicy fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "open", "everyone", "allow" -> OPEN;
                case "closed", "local", "deny" -> CLOSED;
                default -> fallback;
            };
        }
    }

    private boolean enabled;
    private final List<String> administrators;
    private final Map<String, CommandPermission> rules;
    private final DefaultPolicy defaultPolicy;
    private final boolean nameFallbackAllowed;

    private PermissionSet(boolean enabled, List<String> administrators,
                          Map<String, CommandPermission> rules, DefaultPolicy defaultPolicy,
                          boolean nameFallbackAllowed) {
        this.enabled = enabled;
        this.administrators = administrators == null ? List.of() : List.copyOf(administrators);
        this.rules = rules == null ? Map.of() : Map.copyOf(rules);
        this.defaultPolicy = defaultPolicy == null ? DefaultPolicy.OPEN : defaultPolicy;
        this.nameFallbackAllowed = nameFallbackAllowed;
    }

    /** An empty, permissive set: every command open. The state before anything is configured. */
    public static PermissionSet permissive() {
        return new PermissionSet(false, List.of(), Map.of(), DefaultPolicy.OPEN, true);
    }

    /**
     * Builds a set from configuration.
     *
     * @param enabled           the global switch
     * @param administrators    encoded admin entries, each {@code Name} or {@code Name=uuid}
     * @param encodedRules      encoded {@link CommandPermission}s
     * @param policy            how to treat a command with no rule
     * @param nameFallback      whether a name-only entry may match an unverified sender
     */
    public static PermissionSet of(boolean enabled, List<String> administrators,
                                   List<String> encodedRules, DefaultPolicy policy,
                                   boolean nameFallback) {
        Map<String, CommandPermission> parsed = new LinkedHashMap<>();
        if (encodedRules != null) {
            for (String encoded : encodedRules) {
                CommandPermission rule = CommandPermission.decode(encoded);
                if (rule == null || rule.command().isBlank()) continue;
                // Last one wins, so re-adding a command in the GUI replaces rather than duplicates.
                parsed.put(rule.command(), rule);
            }
        }
        List<String> admins = new ArrayList<>();
        if (administrators != null) {
            for (String entry : administrators) {
                String cleaned = CommandPermission.cleanEntry(entry);
                if (cleaned != null) admins.add(cleaned);
            }
        }
        return new PermissionSet(enabled, admins, parsed, policy, nameFallback);
    }

    public boolean enabled() {
        return enabled;
    }

    public DefaultPolicy defaultPolicy() {
        return defaultPolicy;
    }

    public List<String> administrators() {
        return administrators;
    }

    public Map<String, CommandPermission> rules() {
        return rules;
    }

    /** The rule configured for {@code command}, or empty when there is none. */
    public Optional<CommandPermission> rule(String command) {
        if (command == null) return Optional.empty();
        return Optional.ofNullable(rules.get(command.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * The level that actually governs {@code command}.
     *
     * <p>An explicit, enabled rule wins. Otherwise the default policy decides, so the answer is
     * always definite — there is no "unset" that a caller could mistake for "allowed".
     *
     * <p>A rule that is present but disabled resolves to {@link PermissionLevel#DISABLED}, not to
     * the default policy. Switching a rule off must never widen access: a user who disables a
     * whitelist rule would otherwise silently open the command to everyone. It must not narrow to
     * "local only" either, because that would still let the command run — disabled means disabled.
     */
    public PermissionLevel effectiveLevel(String command) {
        CommandPermission rule = rules.get(command == null ? "" : command.trim().toLowerCase(Locale.ROOT));
        if (rule != null) return rule.enabled() ? rule.level() : PermissionLevel.DISABLED;
        return defaultPolicy == DefaultPolicy.CLOSED ? PermissionLevel.LOCAL_ONLY : PermissionLevel.EVERYONE;
    }

    /**
     * The canonical name of a command, so an alias cannot bypass the rule on its real name.
     *
     * <p>Returns the input lower-cased and trimmed when no alias is known.
     */
    public String canonical(String command, Map<String, String> aliases) {
        if (command == null) return "";
        String key = command.trim().toLowerCase(Locale.ROOT);
        if (aliases == null || aliases.isEmpty()) return key;
        return aliases.getOrDefault(key, key);
    }

    /**
     * Decides whether {@code sender} may run {@code command}.
     *
     * <p>This must be called <em>before</em> the command does anything: before memory is written,
     * before a reply is sent, before the AI is asked. A denial returns a reason and no side effect.
     *
     * @param command the canonical command name (already alias-resolved)
     * @param sender  who is asking, as the client can verify them
     */
    public Verdict permits(String command, SenderIdentity sender) {
        if (!enabled) return Verdict.ALLOWED;
        if (command == null || command.isBlank()) return Verdict.denied("Unknown command.");
        if (sender == null) return Verdict.denied("The sender could not be identified.");

        PermissionLevel level = effectiveLevel(command);
        return switch (level) {
            case EVERYONE -> Verdict.ALLOWED;
            case LOCAL_ONLY -> sender.local()
                ? Verdict.ALLOWED
                : Verdict.denied("Only the local player may use " + command + ".");
            case ADMINISTRATORS -> isAdministrator(sender)
                ? Verdict.ALLOWED
                : Verdict.denied(command + " is restricted to administrators.");
            case WHITELIST -> isListed(command, sender)
                ? Verdict.ALLOWED
                : Verdict.denied("You are not on the whitelist for " + command + ".");
            case DISABLED -> Verdict.denied(command + " is switched off.");
        };
    }

    /** True when {@code sender} is on the administrator list. */
    public boolean isAdministrator(SenderIdentity sender) {
        return matchesAny(administrators, sender);
    }

    /** True when {@code sender} is on the whitelist of {@code command}. */
    public boolean isListed(String command, SenderIdentity sender) {
        CommandPermission rule = rules.get(command == null ? "" : command.trim().toLowerCase(Locale.ROOT));
        if (rule == null) return false;
        return matchesAny(rule.users(), sender);
    }

    /**
     * Matches a sender against a list of {@code Name} / {@code Name=uuid} entries.
     *
     * <p>A pinned entry needs the UUID to match, so a spoofed name is refused. A name-only entry
     * needs the name to match and, when name fallback is switched off, also needs the sender to be
     * verified — that is the strict mode for servers that do publish UUIDs.
     */
    private boolean matchesAny(List<String> entries, SenderIdentity sender) {
        if (entries == null || entries.isEmpty() || sender == null) return false;
        String name = sender.name();
        for (String entry : entries) {
            java.util.UUID pinned = CommandPermission.entryUuid(entry);
            if (pinned != null) {
                if (pinned.equals(sender.uuid())) return true;
                continue;
            }
            if (!CommandPermission.entryMatchesName(entry, name)) continue;
            if (!nameFallbackAllowed && !sender.verified()) continue;
            return true;
        }
        return false;
    }

    /** True when the command is restricted in any way, for the GUI's restricted indicator. */
    public boolean restricted(String command) {
        return enabled && effectiveLevel(command) != PermissionLevel.EVERYONE;
    }
}
