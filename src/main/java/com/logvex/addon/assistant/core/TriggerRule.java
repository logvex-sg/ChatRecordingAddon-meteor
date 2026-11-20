package com.logvex.addon.assistant.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One configurable keyword trigger for the assistant.
 *
 * <p>A rule watches incoming chat for a keyword or phrase and, when it matches, produces a response
 * from a template. Every part of the behaviour is a field here so a user can tune it without code:
 * how the keyword is matched, who may trigger it, how the reply is delivered, how often it may fire
 * and whether the match is written to memory.
 *
 * <p>Rules are stored as a single string so they round-trip through the config without a bespoke
 * serialiser: {@code pattern;key=value;key=value}. Unknown keys are ignored, so a rule written by a
 * newer build still loads.
 */
public record TriggerRule(
    String pattern,
    boolean caseSensitive,
    boolean wholeWord,
    String sender,
    String reply,
    ReplyMode mode,
    long cooldownMs,
    int sessionLimit,
    boolean sound,
    boolean remember,
    boolean enabled,
    String aliases,
    String pool,
    String whisper
) {
    /** How a matching rule delivers its reply. */
    public enum ReplyMode {
        /** Say the reply in public chat. */
        CHAT,
        /** Only raise a local notification, never speak. */
        NOTIFY,
        /**
         * Whisper the reply to the speaker with the server's private-message command.
         *
         * <p>This is the mode for a command-shaped trigger such as {@code !tps}: the whole point is
         * that only the asker sees the answer, so the reply goes out as {@code /msg <player> ...}
         * rather than into public chat. The command name is configurable ({@code whisper=}) because
         * the private-message verb differs between servers — {@code /msg}, {@code /tell},
         * {@code /w}, {@code /whisper} — and a wrong one is silently rejected by the server.
         */
        PRIVATE,
        /** Record the match silently, speaking and notifying nothing. */
        SILENT;

        public static ReplyMode parse(String value, ReplyMode fallback) {
            if (value == null) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "chat", "say", "public" -> CHAT;
                case "notify", "alert", "local" -> NOTIFY;
                case "private", "msg", "tell", "whisper", "pm", "dm" -> PRIVATE;
                case "silent", "log", "none", "quiet" -> SILENT;
                default -> fallback;
            };
        }

        /** The encoding used in a stored rule and in {@code ;mode=}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The cooldown a new rule gets, in milliseconds.
     *
     * <p>One second, not five: a chat assistant that ignores a repeated question for five seconds
     * feels broken, and the global per-minute budget already caps the total. It is only a default —
     * an existing rule keeps whatever cooldown it was saved with.
     */
    public static final long DEFAULT_COOLDOWN_MS = 1_000L;

    public TriggerRule {
        if (pattern == null) pattern = "";
        if (sender == null) sender = "";
        if (reply == null) reply = "";
        if (mode == null) mode = ReplyMode.CHAT;
        if (cooldownMs < 0) cooldownMs = 0;
        if (sessionLimit < 0) sessionLimit = 0;
        if (aliases == null) aliases = "";
        if (pool == null) pool = "";
        if (whisper == null || whisper.isBlank()) whisper = DEFAULT_WHISPER;
        whisper = whisper.strip();
    }

    /**
     * The private-message verb used when the mode is {@link ReplyMode#PRIVATE} and the rule does not
     * name one.
     *
     * <p>{@code msg} is the most widely supported spelling across servers. A server that only
     * understands {@code tell} or {@code w} needs the rule's {@code whisper=} set; there is no way
     * for the client to discover which one a server accepts, so guessing wrong is silent.
     */
    public static final String DEFAULT_WHISPER = "msg";

    /** The verb to use, without a leading slash. */
    public String whisperCommand() {
        String value = whisper == null ? "" : whisper.strip();
        return value.isEmpty() ? DEFAULT_WHISPER : value;
    }

    /** True when this rule delivers through the private-message command. */
    public boolean isPrivate() {
        return mode == ReplyMode.PRIVATE;
    }

    /**
     * The verb of the reply when the reply is a server command, or null when it is plain text.
     *
     * <p>A reply beginning with {@code /} is typed into the server as a command rather than spoken,
     * because that is how the chat line treats it. The verb is what decides whether that is safe —
     * see {@link #isSafeCommandReply()}.
     */
    public String commandVerb() {
        if (reply == null) return null;
        String trimmed = reply.strip();
        if (!trimmed.startsWith("/")) return null;
        String body = trimmed.substring(1).strip();
        if (body.isEmpty()) return null;
        int space = body.indexOf(' ');
        String verb = space < 0 ? body : body.substring(0, space);
        verb = verb.toLowerCase(Locale.ROOT);
        return verb.isEmpty() ? null : verb;
    }

    /** True when the reply is a server command rather than chat text. */
    public boolean isCommandReply() {
        return commandVerb() != null;
    }

    /**
     * Verbs that only carry a message to someone, and so cannot change the world.
     *
     * <p>A reply built from one of these is safe to send on behalf of any speaker: the worst a
     * stranger can do is make this client repeat their own words to a player. Every other command
     * is treated as state-changing, which is what {@link #isSafeCommandReply()} refuses.
     */
    private static final java.util.Set<String> COMMUNICATION_VERBS =
        java.util.Set.of("msg", "tell", "w", "whisper", "pm", "dm", "r", "reply", "me", "mail");

    /**
     * True when the reply is a command that cannot change anything, so any speaker may trigger it.
     *
     * <p>This is the guard that stops a stranger in chat from making the client run
     * {@code /tpaccept}, {@code /pay} or {@code /home} by typing the trigger word. Those are allowed
     * only for the local player; a private message is allowed for anyone.
     */
    public boolean isSafeCommandReply() {
        String verb = commandVerb();
        return verb != null && COMMUNICATION_VERBS.contains(verb);
    }

    /**
     * True when this rule may run for {@code senderLocal}, given what its reply would do.
     *
     * <p>Text replies and message-carrying commands are open to everyone. A state-changing command
     * is restricted to the local player, because only they can be assumed to want it run.
     */
    public boolean allowsCommandFrom(boolean senderLocal) {
        if (!isCommandReply()) return true;
        return senderLocal || isSafeCommandReply();
    }

    /**
     * The original eleven-field form, kept so existing call sites and stored rules keep working.
     *
     * <p>Aliases and the reply pool default to empty, which is exactly the behaviour of a rule
     * written before those features existed.
     */
    public TriggerRule(String pattern, boolean caseSensitive, boolean wholeWord, String sender,
                       String reply, ReplyMode mode, long cooldownMs, int sessionLimit,
                       boolean sound, boolean remember, boolean enabled) {
        this(pattern, caseSensitive, wholeWord, sender, reply, mode, cooldownMs, sessionLimit,
            sound, remember, enabled, "", "", DEFAULT_WHISPER);
    }

    /**
     * The thirteen-field form from before private replies, kept so every existing call site and
     * every stored rule keeps loading. A rule written then has no whisper verb, and gets the default.
     */
    public TriggerRule(String pattern, boolean caseSensitive, boolean wholeWord, String sender,
                       String reply, ReplyMode mode, long cooldownMs, int sessionLimit,
                       boolean sound, boolean remember, boolean enabled, String aliases,
                       String pool) {
        this(pattern, caseSensitive, wholeWord, sender, reply, mode, cooldownMs, sessionLimit,
            sound, remember, enabled, aliases, pool, DEFAULT_WHISPER);
    }

    /** A rule that answers {@code pattern} with {@code reply} in chat. */
    public static TriggerRule of(String pattern, String reply) {
        return new TriggerRule(pattern, false, false, "", reply, ReplyMode.CHAT,
            DEFAULT_COOLDOWN_MS, 0, false, false, true);
    }

    /** True when this rule may fire for a message from {@code from}. */
    public boolean senderAllowed(String from) {
        if (sender.isBlank()) return true;
        if (from == null) return false;
        return sender.equalsIgnoreCase(from.trim());
    }

    /** The aliases as a clean list, lower-cased, without blanks or the pattern itself. */
    public List<String> aliasList() {
        if (aliases == null || aliases.isBlank()) return List.of();
        List<String> out = new java.util.ArrayList<>(4);
        for (String alias : aliases.split(",")) {
            String trimmed = alias.trim();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(pattern)) continue;
            out.add(trimmed);
        }
        return List.copyOf(out);
    }

    /** Every phrase this rule answers to: the pattern first, then its aliases. */
    public List<String> phrases() {
        List<String> out = new java.util.ArrayList<>(1 + aliasList().size());
        out.add(pattern);
        out.addAll(aliasList());
        return List.copyOf(out);
    }

    /**
     * The reply to send, with one variant chosen and the pool appended.
     *
     * @param random the randomness source, or null for the shared one
     */
    public String chosenReply(java.util.Random random) {
        return ResponseVariants.withPool(
            ResponseVariants.pick(reply, random), pool, random);
    }

    /** The effective cooldown: never negative. */
    public long effectiveCooldownMs() {
        return Math.max(0, cooldownMs);
    }

    /** True when {@code count} prior replies are still below the session limit. */
    public boolean withinLimit(long count) {
        return sessionLimit <= 0 || count < sessionLimit;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pattern", pattern);
        map.put("case", caseSensitive);
        map.put("whole", wholeWord);
        map.put("sender", sender);
        map.put("reply", reply);
        map.put("mode", mode.name().toLowerCase(Locale.ROOT));
        map.put("cooldown", cooldownMs);
        map.put("limit", sessionLimit);
        map.put("sound", sound);
        map.put("remember", remember);
        map.put("enabled", enabled);
        map.put("aliases", aliases);
        map.put("pool", pool);
        map.put("whisper", whisper);
        return map;
    }

    public static TriggerRule fromMap(Map<?, ?> map) {
        if (map == null) return null;
        String pattern = str(map.get("pattern"));
        if (pattern.isBlank()) return null;
        return new TriggerRule(
            pattern,
            bool(map.get("case"), false),
            bool(map.get("whole"), false),
            str(map.get("sender")),
            str(map.get("reply")),
            ReplyMode.parse(str(map.get("mode")), ReplyMode.CHAT),
            num(map.get("cooldown"), DEFAULT_COOLDOWN_MS),
            (int) num(map.get("limit"), 0),
            bool(map.get("sound"), false),
            bool(map.get("remember"), false),
            bool(map.get("enabled"), true),
            str(map.get("aliases")),
            str(map.get("pool")),
            str(map.get("whisper"))
        );
    }

    public String encode() {
        return pattern + ";case=" + caseSensitive + ";whole=" + wholeWord
            + ";sender=" + sender + ";reply=" + reply + ";mode=" + mode.name().toLowerCase(Locale.ROOT)
            + ";cooldown=" + cooldownMs + ";limit=" + sessionLimit + ";sound=" + sound
            + ";remember=" + remember + ";enabled=" + enabled
            + ";aliases=" + aliases + ";pool=" + pool + ";whisper=" + whisper;
    }

    public static TriggerRule decode(String encoded) {
        return decode(encoded, null);
    }

    /**
     * Decodes a rule, reporting every problem it had to work around.
     *
     * <p>A duplicate key is the case that matters: {@code cooldown=1000;cooldown=3000} has no single
     * sensible reading, and silently taking the last one hides a typo. The rule still decodes — the
     * last value wins, so a hand-edited string keeps working — but the duplicate is reported so the
     * user can see it rather than wonder which cooldown is in force.
     *
     * @param problems receives a readable problem per issue, or null to discard them
     */
    public static TriggerRule decode(String encoded, java.util.List<String> problems) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split(";");
        String pattern = parts[0].strip();
        if (pattern.isEmpty()) return null;

        boolean caseSensitive = false;
        boolean wholeWord = false;
        String sender = "";
        String reply = "";
        ReplyMode mode = ReplyMode.CHAT;
        long cooldown = DEFAULT_COOLDOWN_MS;
        int limit = 0;
        boolean sound = false;
        boolean remember = false;
        boolean enabled = true;
        String aliases = "";
        String pool = "";
        String whisper = DEFAULT_WHISPER;
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            String key = parts[i].substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = parts[i].substring(eq + 1).strip();
            if (!seen.add(key)) {
                if (problems != null) {
                    problems.add("'" + key + "' is set more than once; the last value (" + value
                        + ") wins.");
                }
            }
            switch (key) {
                case "case" -> caseSensitive = Boolean.parseBoolean(value);
                case "whole" -> wholeWord = Boolean.parseBoolean(value);
                case "sender" -> sender = value;
                case "reply" -> reply = value;
                case "mode" -> mode = ReplyMode.parse(value, ReplyMode.CHAT);
                case "cooldown" -> cooldown = parseLong(value, DEFAULT_COOLDOWN_MS);
                case "limit" -> limit = (int) parseLong(value, 0);
                case "sound" -> sound = Boolean.parseBoolean(value);
                case "remember" -> remember = Boolean.parseBoolean(value);
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                case "aliases", "alias" -> aliases = value;
                case "pool", "signoff" -> pool = value;
                case "whisper", "msgcmd", "tell" -> whisper = value;
                default -> {
                    // Unknown key from a newer build: ignore it.
                }
            }
        }

        return new TriggerRule(pattern, caseSensitive, wholeWord, sender, reply, mode,
            cooldown, limit, sound, remember, enabled, aliases, pool, whisper);
    }

    /**
     * The rules a fresh install starts with.
     *
     * <p>Delegates to {@link #preconfigured()} so the settings default and the "reset triggers"
     * action cannot drift apart: a user who resets expects the same table a new install gets.
     */
    public static java.util.List<TriggerRule> defaults() {
        return preconfigured();
    }

    /**
     * The preconfigured trigger table shipped on a fresh install.
     *
     * <p>Every entry uses the 1000 ms default cooldown, matches whole words so {@code hi} does not
     * fire inside {@code this}, and speaks in chat. The point is that a new install answers common
     * chat out of the box instead of shipping one example rule the user has to discover.
     *
     * <p>Kept deliberately free of overlap: no rule's pattern is a substring of another's, so the
     * first-match-wins order in {@link TriggerSet} never decides between two plausible rules.
     * {@code hi} and {@code high} would collide, which is why the greeting rules use the longer
     * forms only.
     */
    public static java.util.List<TriggerRule> preconfigured() {
        java.util.List<TriggerRule> out = new java.util.ArrayList<>();
        // Greetings and courtesies.
        out.add(rule("hello", "Hello {player}!", "hi there,hey there,hello there"));
        out.add(rule("good morning", "Morning, {player}!", "morning"));
        out.add(rule("good night", "Night, {player}.", "gn"));
        out.add(rule("thank you", "You are welcome, {player}.", "thanks,ty,thx"));
        out.add(rule("goodbye", "See you, {player}.", "bye,cya"));
        out.add(rule("good bot", "Thank you, {player}.", "nice bot"));
        out.add(rule("good game", "Good game!", "gg,wp"));
        // Identity and help.
        out.add(rule("who are you", "I am {name}, a local assistant in the client.", "what are you"));
        out.add(rule("what can you do", "I remember facts and answer keywords. Try {prefix}help.",
            "help me,commands"));
        out.add(rule("what is the prefix", "The prefix is {prefix}."));
        // Live figures: these need the {..} placeholders expanded from the current world.
        out.add(rule("what time is it", "It is {time}.", "time now"));
        out.add(rule("where am i", "{coords}, {biome}, facing {facing}.", "my coords,coordinates"));
        out.add(rule("what is my ping", "{ping} ms, {verdict}.", "my latency"));
        out.add(rule("what is the weather", "The sky says {weather}.", "weather"));
        out.add(rule("what server is this", "{server} - {dimension}.", "which server"));
        out.add(rule("what is the tps", "{tps}/20, {mspt} ms per tick.", "server tps"));
        // Small talk that otherwise gets no reply at all.
        out.add(rule("how are you", "Running well, {player}.", "how is it going"));
        out.add(rule("tell me a joke", "I would tell a mining joke, but it is too deep.", "joke"));
        out.add(rule("what do you know", "I know {factcount} facts. Ask {prefix}facts.",
            "your memory"));

        // Public command-shaped rules: typed with the prefix, answered in public chat.
        //
        // A trigger is matched before the command parser, so these do take the place of the
        // same-named command. That is deliberate and safe here: every name below resolves to a
        // command declared EVERYONE, so nothing is exposed that a stranger could not already ask
        // for. The guard test refuses any default that would shadow a LOCAL_ONLY command instead.
        out.add(publicRule("!tps",
            "Current TPS: {tps} | MSPT: {mspt} | Status: {verdict}"));
        out.add(publicRule("!status",
            "Server: {server} | Ping: {ping} | TPS: {tps} | FPS: {fps} | Players: {playercount}"));
        out.add(publicRule("!health",
            "Health: {health} | Absorption: {absorption} | Armor: {armor}"));
        out.add(publicRule("!inventory",
            "Held: {held} | Offhand: {offhand} | Totems: {totems}"));
        out.add(publicRule("!world",
            "World time: {worldtime} | Day: {day} | Weather: {weather}"));
        out.add(publicRule("!client",
            "Client: {clientversion} | Fabric: {fabricversion} | Mods: {modcount} | FPS: {fps}"));
        out.add(publicRule("!ram",
            "Memory used: {memoryused} MB | Free: {memoryfree} MB | Max: {memorymax} MB"
                + " | Usage: {memorypercent}%"));
        out.add(publicRule("!session",
            "Session duration: {sessiontime} | Connected server: {server}"));
        return java.util.List.copyOf(out);
    }

    /**
     * A public rule in the shipped configuration format.
     *
     * <p>The field values are the documented defaults: case-insensitive, whole-word, open to every
     * sender, one second of cooldown, no session limit, no sound, no memory write, enabled.
     */
    private static TriggerRule publicRule(String pattern, String reply) {
        return new TriggerRule(pattern, false, true, "", reply, ReplyMode.CHAT,
            DEFAULT_COOLDOWN_MS, 0, false, false, true, "", "", DEFAULT_WHISPER);
    }

    /** A chat rule at the default cooldown, whole-word matched, with optional aliases. */
    private static TriggerRule rule(String pattern, String reply, String aliases) {
        return new TriggerRule(pattern, false, true, "", reply, ReplyMode.CHAT,
            DEFAULT_COOLDOWN_MS, 0, false, false, true, aliases, "");
    }

    private static TriggerRule rule(String pattern, String reply) {
        return rule(pattern, reply, "");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long num(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return fallback;
        return parseLong(String.valueOf(value), fallback);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
