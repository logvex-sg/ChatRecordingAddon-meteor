package com.logvex.addon.automation.core;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a chat line is a server asking the player to log in.
 *
 * <p>This is the safety-critical half of auto-login. Sending {@code /login <password>} when the
 * server did not ask for it would broadcast the password as a normal chat message, where everyone
 * on the server can read it and this addon would record it. So the rule is deliberately strict: a
 * line only counts when it reads like a login prompt, and a line that came from a player never
 * counts even if it contains the word "login".
 *
 * <p>Minecraft-free so the whole rule set is unit tested. The caller passes the plain text of the
 * message and whether it was attributed to a player.
 */
public final class LoginPromptDetector {
    /**
     * Phrases that appear in a real login prompt. Matched case-insensitively as substrings, because
     * servers wrap them in colour codes, prefixes and decoration.
     */
    private static final List<String> STRONG_PHRASES = List.of(
        "/login",
        "please log in",
        "please login",
        "log in with",
        "login with",
        "use /login",
        "type /login",
        "authenticate",
        "enter your password",
        "password please"
    );

    /** Phrases that mean the server wants a new account, not a login. */
    private static final List<String> REGISTER_PHRASES = List.of(
        "/register",
        "please register",
        "register with",
        "create an account",
        "not registered"
    );

    /** Words that only count as a prompt together with an instruction verb. */
    private static final List<String> PASSWORD_WORDS = List.of("password", "passwort", "mot de passe");

    /** Lines longer than this are prose, not a prompt; real prompts are short. */
    private static final int MAX_LENGTH = 300;

    private LoginPromptDetector() {
    }

    /**
     * True when {@code text} is a server prompt asking the player to log in.
     *
     * @param text       the plain text of the received message
     * @param fromPlayer true when the message was attributed to a player; such messages are never
     *                   treated as prompts, because a player typing "please /login" must not make
     *                   the client send the password
     */
    public static boolean isLoginPrompt(String text, boolean fromPlayer) {
        if (fromPlayer) return false;
        return isLoginPrompt(text);
    }

    /**
     * True when {@code text} is a server asking the player to log in.
     *
     * <p>A registration prompt is not a login prompt: answering {@code /register ...} with
     * {@code /login <password>} would fail, and on some servers it creates nothing while burning an
     * attempt. The caller treats registration separately.
     */
    public static boolean isLoginPrompt(String text) {
        if (text == null) return false;
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) return false;
        String lower = trimmed.toLowerCase(Locale.ROOT);

        for (String phrase : STRONG_PHRASES) {
            if (lower.contains(phrase)) return true;
        }
        // "password" alone is not enough: "what is the password" would trip it. It only counts
        // together with a verb that makes the line an instruction.
        for (String word : PASSWORD_WORDS) {
            if (lower.contains(word) && (lower.contains("type") || lower.contains("enter")
                || lower.contains("use ") || lower.contains("send"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code text} is a server asking the player to register a new account.
     *
     * <p>Used only to explain the situation: a register-only prompt contains no login phrase, so
     * {@link #isLoginPrompt} already declines it. A line offering both ("/login or /register") is a
     * login prompt, because an existing account should log in.
     */
    public static boolean isRegisterPrompt(String text) {
        if (text == null) return false;
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) return false;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String phrase : REGISTER_PHRASES) {
            if (lower.contains(phrase)) return true;
        }
        return false;
    }

    /**
     * True when {@code text} shows the login was accepted, so the client should stop trying.
     *
     * <p>Recognising success matters as much as recognising the prompt: without it a retry policy
     * would keep sending the password on every prompt until it hit its limit.
     */
    public static boolean isLoginSuccess(String text, boolean fromPlayer) {
        if (fromPlayer) return false;
        return isLoginSuccess(text);
    }

    /**
     * True when {@code text} reports a rejected password.
     *
     * <p>A wrong password must not be retried in a loop: servers punish repeated failures, and
     * hammering one is indistinguishable from a brute-force attempt. The caller stops instead.
     */
    public static boolean isLoginFailure(String text, boolean fromPlayer) {
        // Gated on the server: a player typing "wrong password" must not be able to switch
        // auto-login off, which would otherwise be a trivial denial of service.
        if (fromPlayer) return false;
        return isLoginFailure(text);
    }

    /**
     * True when {@code text} shows the login was accepted, so the client should stop trying.
     *
     * <p>Recognising success matters as much as recognising the prompt: without it a retry policy
     * would keep sending the password on every prompt until it hit its limit.
     */
    public static boolean isLoginSuccess(String text) {
        if (text == null) return false;
        String lower = text.strip().toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.length() > MAX_LENGTH) return false;
        return lower.contains("successfully logged in")
            || lower.contains("logged in successfully")
            || lower.contains("login successful")
            || lower.contains("successfully authenticated")
            || lower.contains("you are now logged in")
            || lower.contains("authentication successful");
    }

    /**
     * True when {@code text} reports a rejected password.
     *
     * <p>A wrong password must not be retried in a loop: servers punish repeated failures, and
     * hammering one is indistinguishable from a brute-force attempt. The caller stops instead.
     */
    public static boolean isLoginFailure(String text) {
        if (text == null) return false;
        String lower = text.strip().toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.length() > MAX_LENGTH) return false;
        return lower.contains("wrong password")
            || lower.contains("incorrect password")
            || lower.contains("invalid password")
            || lower.contains("password is incorrect")
            || lower.contains("login failed")
            || lower.contains("authentication failed")
            || lower.contains("too many failed");
    }
}
