package com.logvex.addon.automation.core;

/**
 * Checks and tidies a login password before it is stored.
 *
 * <p>The value is pasted into a server command — {@code /login <password>} — so it is not a free
 * text field. A newline would end the login command and start a second one, a space would split the
 * password into extra arguments, and a value copied from a password manager often arrives with a
 * trailing newline the user cannot see. Each of those fails in a way that looks like "the server
 * rejected my password", which is the worst kind of bug to debug.
 *
 * <p>The rules here are deliberately narrow: they remove what cannot work and explain why, rather
 * than enforcing a policy the server never asked for. A password with punctuation, mixed case or
 * unicode is fine — only characters that break the command are refused.
 *
 * <p>Pure string work with no Minecraft dependency, so every rule is covered by unit tests.
 */
public final class LoginPassword {

    /** Longest password accepted, in characters. */
    public static final int MAX_LENGTH = 128;

    private LoginPassword() {
    }

    /** Why a value cannot be used, or {@link #OK} when it can. */
    public enum Problem {
        OK(""),
        EMPTY("Type a password first."),
        /** Surrounding whitespace is trimmed, but a newline in the middle cannot be repaired. */
        CONTROL_CHARACTER("That password contains a line break or control character. "
            + "Copy just the password itself."),
        /** A space would split {@code /login <password>} into several arguments. */
        CONTAINS_SPACE("That password contains a space or tab, which would break the login command."),
        TOO_LONG("That password is longer than " + MAX_LENGTH
            + " characters. Did a whole file get pasted in?");

        private final String message;

        Problem(String message) {
            this.message = message;
        }

        /** A sentence explaining the problem, or empty for {@link #OK}. */
        public String message() {
            return message;
        }
    }

    /** The value to store, with surrounding whitespace removed. */
    public static String tidy(String raw) {
        return raw == null ? "" : raw.strip();
    }

    /** Checks {@code raw} and returns the first rule it breaks, or {@link Problem#OK}. */
    public static Problem check(String raw) {
        String value = tidy(raw);
        if (value.isEmpty()) return Problem.EMPTY;
        if (value.length() > MAX_LENGTH) return Problem.TOO_LONG;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Checked before isISOControl, because a tab is both: it splits the command like a
            // space, so that is the more useful thing to tell the user.
            if (c == ' ' || c == '\t') return Problem.CONTAINS_SPACE;
            if (c == '\n' || c == '\r' || Character.isISOControl(c)) return Problem.CONTROL_CHARACTER;
        }
        return Problem.OK;
    }

    /** True when {@code raw} can be stored as-is. */
    public static boolean isUsable(String raw) {
        return check(raw) == Problem.OK;
    }
}
