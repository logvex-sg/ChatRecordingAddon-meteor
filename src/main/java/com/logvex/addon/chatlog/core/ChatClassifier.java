package com.logvex.addon.chatlog.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a raw chat line into a {@link MessageType} and extracts the sender when one is
 * identifiable.
 *
 * <p>Everything here is pure text processing so it can be unit tested without a Minecraft runtime.
 * The rules are ordered from most specific to least specific and are intentionally conservative:
 * when nothing matches, a non-chat line falls through to {@link MessageType#SYSTEM} rather than
 * being guessed into a category the addon cannot actually observe.
 */
public final class ChatClassifier {
    /** A vanilla-compatible player name. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern TIMESTAMP = Pattern.compile("^\\[\\d{1,2}:\\d{2}(:\\d{2})?]\\s*");

    private static final Pattern JOIN = Pattern.compile(
        "^(?:\\[[^\\]]*]\\s*)?([A-Za-z0-9_]{1,16})\\s+(?:joined the game|has joined|joined)\\.?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAVE = Pattern.compile(
        "^(?:\\[[^\\]]*]\\s*)?([A-Za-z0-9_]{1,16})\\s+(?:left the game|has left|left|disconnected|quit)\\.?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PM = Pattern.compile(
        "^(?:\\[[^\\]]*]\\s*)?([A-Za-z0-9_]{1,16})\\s+(?:whispers to you|-> you|\\u2192 you|msg(?:es)? you)",
        Pattern.CASE_INSENSITIVE);

    /**
     * A player chat line in the shapes servers use when they strip the signed-chat indicator.
     *
     * <p>Minecraft only attaches a chat indicator to properly signed player chat. An offline-mode
     * server, a proxy, or a server running a chat plugin sends the same line without one, so the
     * indicator cannot be trusted as the sole signal that a player spoke. These patterns recover the
     * sender from the text instead: {@code <Name> text}, {@code [Rank] Name: text} and
     * {@code Name: text}. Without them such a line classified as SYSTEM and was dropped whenever
     * system watching was off, which made triggers fire on some servers and not others.
     */
    private static final Pattern ANGLE_CHAT = Pattern.compile(
        "^(?:\\[[^\\]]{1,32}]\\s*)?<([A-Za-z0-9_]{1,16})>\\s*\\S.*$");
    private static final Pattern COLON_CHAT = Pattern.compile(
        "^(?:\\[[^\\]]{1,32}]\\s*)?([A-Za-z0-9_]{1,16})\\s*:\\s*\\S.*$");

    private static final List<String> DEATH_PHRASES = List.of(
        "was slain by", "was shot by", "was blown up by", "blew up", "drowned", "burned to death",
        "burned to a crisp", "went up in flames", "was killed by", "was killed while trying",
        "withered away", "died", "hit the ground too hard", "tried to swim in lava",
        "discovered the floor was lava", "was impaled", "was fireballed", "suffocated in a wall",
        "starved to death", "was pricked to death", "walked into a cactus", "experienced kinetic energy",
        "was struck by lightning", "froze to death", "was pummeled by", "was skewered by",
        "was stung to death", "was obliterated by", "was shot by a skull from", "walked into fire",
        "was doomed to fall", "was knocked into the void", "fell out of the world",
        "was squashed by", "was squished too much", "was poked to death", "was killed by magic",
        "was killed by even more magic", "was impaled on a stalagmite", "was skewered by a falling stalactite",
        "was struck down by", "was ripped apart", "was crushed by", "fell from a high place",
        "fell too far", "was burnt to a crisp", "was killed"
    );

    private static final List<String> ANNOUNCEMENT_MARKERS = List.of(
        "[server]", "[broadcast]", "[announcement]", "[notice]", "announcement:", "announcement>"
    );

    private static final List<String> GAME_MARKERS = List.of(
        "made the advancement", "has made the advancement", "has completed the challenge",
        "has reached the goal", "you have been", "you are now", "you earned", "you got",
        "welcome to", "set own game mode", "game mode", "has achieved", "your game mode",
        "you can now", "you may not", "objective"
    );

    private ChatClassifier() {
    }

    /** Outcome of classification. */
    public record Result(MessageType type, String sender) {
    }

    /**
     * @param fromPlayer true when Minecraft attached a chat indicator, i.e. the line is signed
     *                   player chat rather than a server/system message
     * @param raw        the plain text of the message
     */
    public static Result classify(boolean fromPlayer, String raw) {
        if (raw == null) return new Result(MessageType.OTHER, "");
        String text = raw.strip();
        if (text.isEmpty()) return new Result(MessageType.OTHER, "");

        if (fromPlayer) {
            Matcher pm = PM.matcher(text);
            if (pm.find()) return new Result(MessageType.PLAYER_MESSAGE, clean(pm.group(1)));
            return new Result(MessageType.PUBLIC_CHAT, extractSender(text));
        }

        String body = TIMESTAMP.matcher(text).replaceFirst("").strip();
        String lower = body.toLowerCase(Locale.ROOT);

        Matcher join = JOIN.matcher(body);
        if (join.matches()) return new Result(MessageType.JOIN, clean(join.group(1)));

        Matcher leave = LEAVE.matcher(body);
        if (leave.matches()) return new Result(MessageType.LEAVE, clean(leave.group(1)));

        if (isDeath(lower)) return new Result(MessageType.DEATH, firstToken(body));

        for (String marker : ANNOUNCEMENT_MARKERS) {
            if (lower.startsWith(marker) || lower.contains(marker)) {
                return new Result(MessageType.ANNOUNCEMENT, bracketTag(body));
            }
        }

        for (String marker : GAME_MARKERS) {
            if (lower.contains(marker)) return new Result(MessageType.GAME, "");
        }

        // Player chat whose signed-chat indicator was stripped by a proxy or chat plugin. Checked
        // before the bracketed-system fallback: a rank prefix such as "[VIP] Steve: hi" is a player
        // talking, not a system notice, and treating it as one is what silently dropped triggers.
        Matcher angle = ANGLE_CHAT.matcher(body);
        if (angle.matches()) {
            String name = clean(angle.group(1));
            if (isPlayerName(name)) return new Result(MessageType.PUBLIC_CHAT, name);
        }

        Matcher colon = COLON_CHAT.matcher(body);
        if (colon.matches()) {
            String name = clean(colon.group(1));
            if (isPlayerName(name) && !looksLikeSystemTag(name)) {
                return new Result(MessageType.PUBLIC_CHAT, name);
            }
        }

        if (body.startsWith("[") && body.indexOf(']') > 1 && body.indexOf(']') < 24) {
            return new Result(MessageType.SYSTEM, bracketTag(body));
        }

        return new Result(MessageType.SYSTEM, "");
    }

    /**
     * True when a colon-prefixed word is a system tag rather than a player name.
     *
     * <p>{@code Server: restarting} and {@code Announcement: event} use the same shape as a player
     * name, so the vocabulary has to decide. Only a known tag is rejected; an unknown word is
     * treated as a player, because a missed reply is worse than an extra one.
     */
    private static boolean looksLikeSystemTag(String word) {
        if (word == null) return true;
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.equals("server") || lower.equals("system") || lower.equals("announcement")
            || lower.equals("broadcast") || lower.equals("notice") || lower.equals("warning")
            || lower.equals("error") || lower.equals("info") || lower.equals("admin")
            || lower.equals("console") || lower.equals("note") || lower.equals("tip")
            || lower.equals("hint") || lower.equals("news") || lower.equals("event");
    }

    /**
     * True when {@code name} can be a Minecraft player name.
     *
     * <p>Vanilla allows 3 to 16 characters, so the length floor rejects a clock reading such as
     * {@code 12:34}, and the letter requirement rejects a numeric label. Without both, an ordinary
     * line of text shaped like {@code digits: digits} was read as somebody speaking.
     */
    private static boolean isPlayerName(String name) {
        if (name == null || name.length() < 3 || name.length() > 16) return false;
        boolean hasLetter = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
            if (Character.isLetter(c)) hasLetter = true;
        }
        return hasLetter;
    }

    /** Best-effort sender extraction from a decorated chat line such as {@code <Name> message}. */
    public static String extractSender(String text) {
        if (text == null) return "";
        String body = text.strip();

        int close = body.indexOf('>');
        if (close > 0 && close <= 64) {
            String prefix = body.substring(0, close).strip();
            if (prefix.startsWith("<")) prefix = prefix.substring(1).strip();
            return clean(lastToken(prefix));
        }

        int colon = body.indexOf(':');
        if (colon > 0 && colon <= 24) {
            return clean(lastToken(body.substring(0, colon).strip()));
        }

        return "";
    }

    private static boolean isDeath(String lower) {
        for (String phrase : DEATH_PHRASES) {
            if (lower.contains(phrase)) return true;
        }
        return false;
    }

    private static String firstToken(String body) {
        int space = body.indexOf(' ');
        String token = space < 0 ? body : body.substring(0, space);
        return clean(token);
    }

    private static String bracketTag(String body) {
        int open = body.indexOf('[');
        int close = body.indexOf(']');
        if (open == 0 && close > 1) return body.substring(1, close).strip();
        return "";
    }

    private static String lastToken(String prefix) {
        int space = prefix.lastIndexOf(' ');
        return space >= 0 ? prefix.substring(space + 1) : prefix;
    }

    /** Keeps only characters legal in a player name; returns "" when the token is not a name. */
    private static String clean(String token) {
        if (token == null) return "";
        String trimmed = token.strip();
        if (trimmed.isEmpty()) return "";
        if (NAME.matcher(trimmed).matches()) return trimmed;

        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length() && sb.length() < 16; i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        String cleaned = sb.toString();
        return NAME.matcher(cleaned).matches() ? cleaned : "";
    }
}
