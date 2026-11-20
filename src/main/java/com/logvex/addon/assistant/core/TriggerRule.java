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
    String whisper,
    EmbedMode embedMode
) {
    /**
     * The shipped form, for every rule that does not name a layout.
     *
     * <p>Kept so a rule can be built without naming the layout, which is what almost every caller
     * and every test wants. The default is {@link EmbedMode#AUTO}: a reply that is written as
     * labelled clauses becomes a block, and a single line stays a single line.
     */
    public TriggerRule(String pattern, boolean caseSensitive, boolean wholeWord, String sender,
                       String reply, ReplyMode mode, long cooldownMs, int sessionLimit,
                       boolean sound, boolean remember, boolean enabled, String aliases,
                       String pool, String whisper) {
        this(pattern, caseSensitive, wholeWord, sender, reply, mode, cooldownMs, sessionLimit,
            sound, remember, enabled, aliases, pool, whisper, EmbedMode.AUTO);
    }

    /** How a rule's reply is laid out when it is delivered. */
    public enum EmbedMode {
        /**
         * Render a block when the reply is written as labelled clauses, otherwise keep one line.
         *
         * <p>The default: {@code "TPS 19.9, MSPT 12"} becomes aligned rows, while {@code "hello"}
         * stays a single line instead of gaining a border it does not need.
         */
        AUTO,
        /** Always render a block, even for a single value. */
        ON,
        /** Never render a block; send the reply as written. */
        OFF;

        public static EmbedMode parse(String value, EmbedMode fallback) {
            if (value == null || value.isBlank()) return fallback;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto", "detect" -> AUTO;
                case "on", "true", "yes", "1", "box", "block", "embed" -> ON;
                case "off", "false", "no", "0", "none", "line", "plain" -> OFF;
                default -> fallback;
            };
        }

        /** The stored form: {@code auto}, {@code on} or {@code off}. */
        public String encoded() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * True when this rule is explicitly set to always render a block.
     *
     * <p>{@link EmbedMode#AUTO} reports false here because whether it renders depends on the reply
     * text, which this method cannot see; use {@link #wantsEmbed(String)} for the real answer.
     */
    public boolean embed() {
        return embedMode == EmbedMode.ON;
    }

    /**
     * Whether {@code expanded} should be delivered as a block under this rule's mode.
     *
     * <p>{@link EmbedMode#AUTO} asks for a block only when both hold: the pattern is command-shaped
     * (it starts with {@code !} or {@code /}), and the reply is two or more clauses that every one
     * of carries a label. That combination is what a figures answer looks like — {@code "TPS 19.9,
     * MSPT 12"}. An ordinary phrase trigger is left alone however many commas its reply has, and a
     * trailing bare value such as {@code "Ping 42 ms, good"} keeps its one line, because there is no
     * second label to align against.
     */
    public boolean wantsEmbed(String expanded) {
        return switch (embedMode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> isCommandShaped() && labelledClauses(expanded) >= 2;
        };
    }

    /** True when the pattern is a prefixed command such as {@code !tps}, not a chat phrase. */
    public boolean isCommandShaped() {
        return pattern != null && !pattern.isBlank()
            && (pattern.charAt(0) == '!' || pattern.charAt(0) == '/');
    }

    /** How many clauses carry a label; a bare value or a one-word clause does not count. */
    private static int labelledClauses(String expanded) {
        int labelled = 0;
        int total = 0;
        for (ReplyFormat.Field field : clauses(expanded)) {
            total++;
            if (!field.label().isBlank() && !field.value().isBlank()) labelled++;
        }
        return total > 0 && labelled == total ? labelled : 0;
    }
    /** How a matching rule delivers its reply. */
    public enum ReplyMode {
        /** Say the reply in public chat. Opt in: the default is {@link #PRIVATE}. */
        CHAT,
        /** Only raise a local notification, never speak. */
        NOTIFY,
        /**
         * Whisper the reply to the speaker with the server's private-message command.
         *
         * <p>This is the default, and the mode for a command-shaped trigger such as {@code !tps}:
         * only the asker sees the answer, so the reply goes out as {@code /msg <player> ...}
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

    /**
     * The cooldown every shipped rule carries, in milliseconds.
     *
     * <p>Three seconds, unlike the one-second default a hand-written rule gets. The shipped table is
     * large and every entry answers out of the box, so on a busy server a shorter gap lets two or
     * three players trade the same keyword back and forth until chat is nothing but the assistant.
     * Cooldowns are tracked per speaker, so this paces each player without muting the rule for
     * everyone else.
     */
    public static final long TRIGGER_COOLDOWN_MS = 3_000L;

    public TriggerRule {
        if (pattern == null) pattern = "";
        if (sender == null) sender = "";
        if (reply == null) reply = "";
        if (mode == null) mode = ReplyMode.PRIVATE;
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
        return new TriggerRule(pattern, false, false, "", reply, ReplyMode.PRIVATE,
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
     * The reply to send, exactly as configured.
     *
     * <p>{@code reply} is a literal line and no pool sentence is appended, so a rule always answers
     * the same way. A pipe left in a reply by an older build is not a random choice: its first
     * alternative is used, so a migrated rule renders a clean line instead of printing the pipe.
     * {@code pool} is retained only so a stored rule still round-trips; nothing reads it.
     *
     * @param random unused, kept so existing call sites and tests do not change
     */
    public String chosenReply(java.util.Random random) {
        return ResponseVariants.first(reply);
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
        map.put("embed", embedMode.encoded());
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
            ReplyMode.parse(str(map.get("mode")), ReplyMode.PRIVATE),
            num(map.get("cooldown"), DEFAULT_COOLDOWN_MS),
            (int) num(map.get("limit"), 0),
            bool(map.get("sound"), false),
            bool(map.get("remember"), false),
            bool(map.get("enabled"), true),
            str(map.get("aliases")),
            str(map.get("pool")),
            str(map.get("whisper")),
            EmbedMode.parse(str(map.get("embed")), EmbedMode.AUTO)
        );
    }

    public String encode() {
        return pattern + ";case=" + caseSensitive + ";whole=" + wholeWord
            + ";sender=" + sender + ";reply=" + reply + ";mode=" + mode.name().toLowerCase(Locale.ROOT)
            + ";cooldown=" + cooldownMs + ";limit=" + sessionLimit + ";sound=" + sound
            + ";remember=" + remember + ";enabled=" + enabled
            + ";aliases=" + aliases + ";pool=" + pool + ";whisper=" + whisper
            + ";embed=" + embedMode.encoded();
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
        ReplyMode mode = ReplyMode.PRIVATE;
        long cooldown = DEFAULT_COOLDOWN_MS;
        int limit = 0;
        boolean sound = false;
        boolean remember = false;
        boolean enabled = true;
        String aliases = "";
        String pool = "";
        String whisper = DEFAULT_WHISPER;
        EmbedMode embedMode = EmbedMode.AUTO;
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
                case "mode" -> mode = ReplyMode.parse(value, ReplyMode.PRIVATE);
                case "cooldown" -> cooldown = parseLong(value, DEFAULT_COOLDOWN_MS);
                case "limit" -> limit = (int) parseLong(value, 0);
                case "sound" -> sound = Boolean.parseBoolean(value);
                case "remember" -> remember = Boolean.parseBoolean(value);
                case "enabled" -> enabled = Boolean.parseBoolean(value);
                case "aliases", "alias" -> aliases = value;
                case "pool", "signoff" -> pool = value;
                case "whisper", "msgcmd", "tell" -> whisper = value;
                case "embed", "block", "format" -> embedMode = EmbedMode.parse(value, EmbedMode.AUTO);
                default -> {
                    // Unknown key from a newer build: ignore it.
                }
            }
        }

        return new TriggerRule(pattern, caseSensitive, wholeWord, sender, reply, mode,
            cooldown, limit, sound, remember, enabled, aliases, pool, whisper, embedMode);
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
     * <p>Every entry whispers its answer to whoever asked, matches whole words, and carries
     * {@link #TRIGGER_COOLDOWN_MS}. A reply is one literal line: no alternatives and no appended
     * sentence, so the same question always prints the same answer.
     *
     * <p>Three properties are load-bearing and are pinned by tests, because breaking any of them
     * turns a helpful table into a noisy or unsafe one:
     *
     * <ul>
     *   <li><b>No overlap.</b> No rule's pattern or alias is a substring of another's, so the
     *       first-match-wins order in {@link TriggerSet} never has to break a tie. This is why the
     *       greeting rules use the longer forms ({@code hi there}, never a bare {@code hi}, which
     *       would sit inside {@code this}) and why the tps family is spelled out per figure.</li>
     *   <li><b>Correct units.</b> A placeholder is expanded as written, so a template that appends
     *       its own unit to a value that already carries one prints {@code 42 msms}. {@code {ping}},
     *       {@code {mspt}} and the memory figures are unit-free; {@code {tps}} is a bare number;
     *       {@code {worldtime}} already ends in {@code day}/{@code night}.</li>
     *   <li><b>No private figure in a public reply.</b> Every rule here is whispered, so a reply
     *       naming a coordinate never reaches the rest of the server.</li>
     * </ul>
     *
     * <p>A rule whose reply describes the player (coordinates, gear, health) is still refused to a
     * remote sender by the runtime, so a stranger cannot read the operator's position by typing
     * {@code where am i}.
     */
    public static java.util.List<TriggerRule> preconfigured() {
        java.util.List<TriggerRule> out = new java.util.ArrayList<>();

        // ---------------------------------------------------------------- greetings and courtesies
        out.add(rule("hello", "Hello {player}!", "hi there,hey there,hello there"));
        out.add(rule("good morning", "Morning, {player}!", "morning"));
        out.add(rule("good night", "Night, {player}.", "gn"));
        out.add(rule("thank you", "You are welcome, {player}.", "thanks,ty,thx"));
        out.add(rule("goodbye", "See you, {player}.", "bye,cya"));
        out.add(rule("good bot", "Thank you, {player}.", "nice bot"));
        out.add(rule("good game", "Good game!", "gg,wp"));
        out.add(rule("welcome back", "Welcome back, {player}.", "wb"));
        out.add(rule("well played", "Well played.", "nice one"));
        out.add(rule("how are you", "Running well, {player}.", "how is it going"));
        out.add(rule("nice to meet you", "Likewise, {player}.", "pleased to meet you"));
        out.add(rule("see you later", "Later, {player}.", "see you soon"));
        out.add(rule("have a good day", "You too, {player}.", "have a nice day"));
        out.add(rule("sorry", "No harm done, {player}.", "my bad,apologies"));
        out.add(rule("congratulations", "Congratulations, {player}!", "congrats"));
        out.add(rule("happy birthday", "Happy birthday, {player}!", "hbd"));
        out.add(rule("good luck", "Good luck, {player}!", "gl"));
        out.add(rule("no problem", "No problem.", "np"));
        out.add(rule("you are welcome", "Any time.", "youre welcome"));
        out.add(rule("laughing out loud", "Glad that landed.", "lol,lmao"));

        // ---------------------------------------------------------------- identity and help
        out.add(rule("who are you", "{name}", "what are you"));
        out.add(rule("what can you do", "{prefix}help",
            "help me,commands,what commands are there,!help"));
        out.add(rule("what is the prefix", "{prefix}"));
        out.add(rule("what do you know", "{factcount} facts. {prefix}facts", "your memory"));
        out.add(rule("tell me a joke", "Mining joke: it is too deep.", "joke"));
        out.add(rule("what is your name", "I am {name}.", "your name"));
        out.add(rule("are you a bot", "I am {name}, running in this client.", "are you real"));
        out.add(rule("can you see this", "Yes, I read chat.", "do you read chat"));
        out.add(rule("how do i use you", "{prefix}help lists every command.", "how do i ask"));
        out.add(rule("are you recording", "This client logs chat locally.", "do you log chat"));
        out.add(rule("do you report me", "Only what you file yourself with {prefix}report.",
            "are you a snitch"));

        // ---------------------------------------------------------------- live figures, asked in prose
        //
        // Units are already inside the value where the value carries one, so no template appends
        // "ms" or "/20" to {ping}, {mspt} or {tps}.
        out.add(rule("what time is it", "{worldtime}", "time now"));
        out.add(rule("where am i", "{coords} in {biome}, facing {facing}", "my coords,coordinates"));
        out.add(rule("what is my ping", "{ping}, {verdict}", "my latency"));
        out.add(rule("what is the weather", "{weather}", "weather now"));
        out.add(rule("what server is this", "{server} ({dimension})", "which server"));
        out.add(rule("what is the tps", "TPS {tps}, {mspt} ms per tick, {verdict}", "server tps"));
        out.add(rule("what is the mspt", "{mspt} ms per tick", "tick time"));
        out.add(rule("what is the tick rate", "TPS {tps}, {mspt} ms per tick", "server speed"));
        out.add(rule("how many players", "{playercount} of {maxplayers}", "player count"));
        out.add(rule("who is online", "{playercount} players. {prefix}who", "online now"));
        out.add(rule("what is my health", "{health} hp, {food} food", "my hp"));
        out.add(rule("what is my armour", "{armor} points", "my armor,what is my armor"));
        out.add(rule("what am i holding", "{held}, offhand {offhand}", "my hand"));
        out.add(rule("what is my gamemode", "{gamemode}", "my mode"));
        out.add(rule("what is my level", "Level {level}, {xp} to next"));
        out.add(rule("what is my speed", "{speed} blocks/s", "how fast am i"));
        out.add(rule("what effects do i have", "{effects}", "my potions"));
        out.add(rule("what dimension is this", "{dimension}", "which dimension"));
        out.add(rule("what is the difficulty", "{difficulty}", "server difficulty"));
        out.add(rule("what day is it", "World day {day}, time {worldtime}", "world day"));
        out.add(rule("what is my fps", "{fps} fps", "my framerate"));
        out.add(rule("how long have i been on", "{sessiontime}", "my session"));
        out.add(rule("how much memory", "{memoryused} of {memorymax} MB, {memorypercent}%",
            "client ram"));
        out.add(rule("what is my xp", "Level {level}, {xp} to next", "my experience"));
        out.add(rule("what is my hunger", "{food} food, {saturation} saturation", "my food"));
        out.add(rule("what is my air", "{air} air", "bubble level"));

        // ---------------------------------------------------------------- server figures in prose
        out.add(rule("what is the server brand", "{serverbrand}", "server software"));
        out.add(rule("what version is the server", "{serverversion}", "server version"));
        out.add(rule("how long has the server run", "{serveruptime}", "server uptime"));
        out.add(rule("is the server lagging", "TPS {tps}, {verdict}", "is it laggy"));
        out.add(rule("am i lagging", "{ping}, {verdict}", "my connection"));
        out.add(rule("is the server up", "{serverstatus}", "server state"));
        out.add(rule("what client is this", "Client {clientversion}, Fabric {fabricversion}",
            "client version"));
        out.add(rule("how many mods", "Mods loaded: {modcount}", "mod count"));

        // ---------------------------------------------------------------- command-shaped rules
        //
        // A trigger is matched before the command parser, so a prefixed rule does take the place of
        // the same-named command. Two rules follow from that, and a guard test enforces both:
        //
        //   * only an EVERYONE command may be shadowed, never a LOCAL_ONLY one - shadowing
        //     "!coords" would hand a stranger the operator's position that the command itself
        //     refuses to them;
        //   * the shadowing reply must be at least as useful as the command. That is why this list
        //     is single-figure commands only: "!help", "!facts" or "!macros" are replaced by their
        //     real listing, not by a stub saying to type the command again.
        //
        // Each reply is comma separated, never pipe separated: ResponseVariants reads a pipe as
        // "pick one alternative", which is the opposite of a field separator.
        out.add(embedRule("!tps", "TPS {tps}, MSPT {mspt} ms, Status {verdict}"));
        out.add(embedRule("!status",
            "Server {server}, Ping {ping}, TPS {tps}, FPS {fps}, Players {playercount}"));
        out.add(commandRule("!ping", "Ping {ping}, {verdict}"));
        out.add(commandRule("!players", "Online {playercount} of {maxplayers}"));
        out.add(commandRule("!uptime", "Session {uptime}"));
        out.add(commandRule("!session", "Session {uptime}"));
        out.add(commandRule("!fps", "FPS {fps}"));
        out.add(commandRule("!ram", "Heap {memoryused} of {memorymax} MB, {memorypercent}%"));
        out.add(embedRule("!memory", "Used {memoryused} MB, Max {memorymax} MB, Load {memorypercent}%"));
        out.add(embedRule("!world", "Dimension {dimension}, Biome {biome}"));
        out.add(embedRule("!time", "Time {worldtime}, Day {day}"));
        out.add(commandRule("!day", "Day {day}"));
        out.add(commandRule("!weather", "Weather {weather}"));
        out.add(commandRule("!gamemode", "Gamemode {gamemode}"));
        out.add(embedRule("!health", "Health {health}, Absorption {absorption}, Armour {armor}"));
        out.add(commandRule("!speed", "Speed {speed} blocks/s"));
        out.add(commandRule("!who", "Online {playercount} of {maxplayers}"));

        return java.util.List.copyOf(out);
    }

    /**
     * A command-shaped rule: whispered to the speaker, whole-word matched, at the shipped cooldown.
     *
     * <p>The field values are explicit and complete — case-insensitive, whole-word, open to every
     * sender, {@link #TRIGGER_COOLDOWN_MS} of cooldown, no session limit, no sound, no memory write,
     * enabled, no aliases, no pool, the default whisper verb — so a shipped rule never depends on
     * what an absent field would have defaulted to.
     */
    private static TriggerRule commandRule(String pattern, String reply) {
        return new TriggerRule(pattern, false, true, "", reply, ReplyMode.PRIVATE,
            TRIGGER_COOLDOWN_MS, 0, false, false, true, "", "", DEFAULT_WHISPER);
    }

    /**
     * A command-shaped rule delivered as a labelled block.
     *
     * <p>Used for the rules with several figures, which is where a block earns its extra lines: the
     * one-line form stays as the fallback, and a user who prefers it sets {@code embed=off} on the
     * rule or picks a plain layout in the module settings.
     */
    private static TriggerRule embedRule(String pattern, String reply) {
        return new TriggerRule(pattern, false, true, "", reply, ReplyMode.PRIVATE,
            TRIGGER_COOLDOWN_MS, 0, false, false, true, "", "", DEFAULT_WHISPER, EmbedMode.ON);
    }

    /** A whispered rule at the shipped cooldown, whole-word matched, with optional aliases. */
    private static TriggerRule rule(String pattern, String reply, String aliases) {
        return new TriggerRule(pattern, false, true, "", reply, ReplyMode.PRIVATE,
            TRIGGER_COOLDOWN_MS, 0, false, false, true, aliases, "", DEFAULT_WHISPER);
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

    /**
     * This rule's reply as a labelled block.
     *
     * <p>A reply is written as comma-separated {@code Label value} clauses, which is how the
     * shipped table reads anyway: {@code "TPS {tps}, MSPT {mspt}, verdict {verdict}"}. Each clause
     * becomes one aligned row, so the figures line up instead of running together. A clause with no
     * space has no label and keeps its text as the value, so a bare reply still renders.
     *
     * <p>Minecraft chat cannot carry a newline inside one message, so the caller sends the returned
     * lines in order. That is why the block is built here rather than inside a placeholder.
     *
     * @param expanded the already-expanded reply text
     * @param title    the block heading, usually the rule's pattern
     */
    public ReplyFormat.Embed toEmbed(String expanded, String title) {
        return new ReplyFormat.Embed(title == null ? "" : title, clauses(expanded), "",
            ReplyFormat.Style.BOX);
    }

    /**
     * Splits a reply into labelled rows.
     *
     * <p>The first word of a clause is its label and the rest is the value. A clause of one word
     * keeps it as an unlabelled value, so {@code "hello"} renders as a single row rather than
     * vanishing. An empty reply yields no rows at all.
     */
    static java.util.List<ReplyFormat.Field> clauses(String expanded) {
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        String text = expanded == null ? "" : expanded.strip();
        if (text.isEmpty()) return java.util.List.copyOf(fields);
        for (String clause : text.split(",\\s*")) {
            String part = clause.strip();
            if (part.isEmpty()) continue;
            int space = part.indexOf(' ');
            if (space <= 0) {
                fields.add(new ReplyFormat.Field("", part));
            } else {
                fields.add(new ReplyFormat.Field(
                    part.substring(0, space).strip(), part.substring(space + 1).strip()));
            }
        }
        return java.util.List.copyOf(fields);
    }
}
