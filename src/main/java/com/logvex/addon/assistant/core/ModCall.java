package com.logvex.addon.assistant.core;

import java.util.Locale;

/**
 * Builds the private message that asks a moderator for help.
 *
 * <p>{@code !modcall <reason>} sends one {@code /msg <target> <reason>} to a moderator the user
 * configured, so the request reaches them as a direct message rather than as public chat the
 * moderator may never read.
 *
 * <p>The reason is typed by a player and is pasted into a server command, so it is not trusted:
 * a newline would send a second, unrelated command, and the message is capped so one call cannot
 * become a wall of text in the moderator's inbox. Everything here is pure string work and takes the
 * clock as a parameter, so the rules can be tested without a game running.
 */
public final class ModCall {

    /** Longest reason forwarded to the moderator, in characters. */
    public static final int MAX_REASON_LENGTH = 180;

    private ModCall() {
    }

    /** Why a call could not be built, or {@link #NONE} when it could. */
    public enum Problem {
        NONE(""),
        /** No target is configured, so there is nobody to message. */
        NO_TARGET("No moderator is configured. Set a target in the modcall module's settings."),
        /** The configured target is not a legal player name. */
        BAD_TARGET("The configured modcall target is not a valid player name."),
        /** The player did not say what they need. */
        NO_REASON("Say what you need help with: {prefix}modcall <reason>."),
        /** The reason was nothing but control characters or whitespace. */
        EMPTY_REASON("That reason had no readable text in it.");

        private final String message;

        Problem(String message) {
            this.message = message;
        }

        /** The explanation to show the player, or an empty string for {@link #NONE}. */
        public String message() {
            return message;
        }

        /** The same explanation with {@code {prefix}} replaced by the configured prefix. */
        public String message(String prefix) {
            String p = prefix == null || prefix.isBlank() ? "!" : prefix;
            return message.replace("{prefix}", p);
        }
    }

    /** What the moderator is sent by default. */
    public static final String DEFAULT_MODERATOR_FORMAT = "Report has been submitted: {reason}";

    /** What the reporter is sent by default, as a receipt of what went out. */
    public static final String DEFAULT_REPORTER_FORMAT = "!report {reason}";

    /**
     * One built call.
     *
     * @param commands the server commands to send, in order, without a leading slash
     * @param target   the moderator the first message goes to
     * @param reason   the reason as it will be sent, after sanitising and truncation
     * @param problem  {@link Problem#NONE} when {@code commands} may be sent
     */
    public record Call(java.util.List<String> commands, String target, String reason,
                       Problem problem) {
        public Call {
            commands = commands == null ? java.util.List.of() : java.util.List.copyOf(commands);
        }

        /** True when the call is complete and safe to send. */
        public boolean ok() {
            return problem == Problem.NONE && !commands.isEmpty();
        }

        /** The first command, or an empty string; the moderator message is always first. */
        public String command() {
            return commands.isEmpty() ? "" : commands.get(0);
        }

        /** A call that cannot be sent, carrying the reason it cannot. */
        public static Call failed(Problem problem) {
            return new Call(java.util.List.of(), "", "",
                problem == null ? Problem.NO_REASON : problem);
        }
    }

    /**
     * Builds the call, or explains why it cannot be built.
     *
     * @param target the configured moderator name; may be blank or invalid
     * @param reason what the player asked for
     * @param prefix the command prefix, used only to word the usage hint
     */
    public static Call build(String target, String reason, String prefix) {
        return build(target, reason, prefix, DEFAULT_MODERATOR_FORMAT, DEFAULT_REPORTER_FORMAT);
    }

    /** Convenience overload that uses the default prefix in the usage hint. */
    public static Call build(String target, String reason) {
        return build(target, reason, "!", DEFAULT_MODERATOR_FORMAT, DEFAULT_REPORTER_FORMAT);
    }

    /**
     * Builds both messages: one to the moderator, one receipt to the reporter.
     *
     * <p>The two are separate on purpose. The moderator needs to know what was asked and by whom;
     * the reporter needs to see that it went out, so they are not left guessing. Both formats are
     * settings, so the wording can be changed without touching code.
     *
     * <p>An empty reporter format skips the receipt, which is what a user who does not want to
     * message themselves should set.
     *
     * @param reporter the player who asked; used for the receipt and the {@code {reporter}} token
     */
    public static Call build(String target, String reason, String prefix,
                             String moderatorFormat, String reporterFormat, String reporter) {
        String wanted = target == null ? "" : target.strip();
        if (wanted.isEmpty()) return Call.failed(Problem.NO_TARGET);
        if (!SenderIdentity.validName(wanted)) return Call.failed(Problem.BAD_TARGET);

        String said = reason == null ? "" : reason.strip();
        if (said.isEmpty()) return Call.failed(Problem.NO_REASON);

        String clean = sanitise(said);
        if (clean.isEmpty()) return Call.failed(Problem.EMPTY_REASON);

        String who = reporter == null ? "" : reporter.strip();
        java.util.List<String> commands = new java.util.ArrayList<>(2);
        commands.add("msg " + wanted + " " + render(moderatorFormat, clean, wanted, who, prefix));

        String receipt = render(reporterFormat, clean, wanted, who, prefix);
        if (!receipt.isEmpty() && SenderIdentity.validName(who)) {
            commands.add("msg " + who + " " + receipt);
        }
        return new Call(commands, wanted, clean, Problem.NONE);
    }

    /** The three-argument form, with the default formats and no reporter receipt. */
    public static Call build(String target, String reason, String prefix,
                             String moderatorFormat, String reporterFormat) {
        return build(target, reason, prefix, moderatorFormat, reporterFormat, "");
    }

    /**
     * Substitutes the tokens in one format line, then sanitises the result.
     *
     * <p>Substitution is a single pass over the template, so text that <em>arrives</em> through a
     * token is never scanned for tokens again. Chained {@code replace} calls would do exactly that:
     * a reason containing {@code {reporter}} would be expanded into the reporter's name, letting a
     * player forge text attributed to someone else.
     *
     * <p>Sanitising happens after substitution because the reason is the untrusted part: a format
     * can be edited freely, but whatever it expands to still has to be one safe line.
     */
    public static String render(String format, String reason, String moderator, String reporter,
                                String prefix) {
        String template = format == null ? "" : format;
        String p = prefix == null || prefix.isBlank() ? "!" : prefix;
        String safeReason = reason == null ? "" : reason;
        String safeModerator = moderator == null ? "" : moderator;
        String safeReporter = reporter == null ? "" : reporter;

        StringBuilder out = new StringBuilder(template.length() + safeReason.length());
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            int close = template.indexOf('}', open + 1);
            if (close < 0) {
                // An unclosed brace is literal text, not a broken token.
                out.append(template, open, template.length());
                break;
            }
            String token = template.substring(open, close + 1);
            switch (token) {
                case "{reason}" -> out.append(safeReason);
                case "{moderator}" -> out.append(safeModerator);
                case "{reporter}" -> out.append(safeReporter);
                case "{prefix}" -> out.append(p);
                // Anything else is not a token we know, so it stays exactly as the user typed it.
                default -> out.append(token);
            }
            i = close + 1;
        }
        return sanitise(out.toString());
    }

    /**
     * Makes {@code text} safe to place after a command.
     *
     * <p>Every control character is replaced with a space, which collapses newlines — the one that
     * matters, because a newline in a sent command is a second command — and drops the rest of the
     * formatting characters that would otherwise be pasted into chat. Runs of whitespace are
     * collapsed, and the result is capped at {@link #MAX_REASON_LENGTH}.
     */
    public static String sanitise(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_REASON_LENGTH));
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length() && out.length() < MAX_REASON_LENGTH; i++) {
            char c = text.charAt(i);
            // A section sign starts a chat formatting code: the sign and the letter after it are
            // one unit. Dropping only the sign would leave the bare letter behind, so the whole
            // code is skipped and the message stays plain text.
            if (c == '\u00a7') {
                if (i + 1 < text.length()) i++;
                continue;
            }
            // Control characters include newline and carriage return; neither belongs in a
            // one-line command.
            boolean space = Character.isWhitespace(c) || Character.isISOControl(c);
            if (space) {
                if (out.length() > 0 && !lastWasSpace) out.append(' ');
                lastWasSpace = true;
            } else {
                out.append(c);
                lastWasSpace = false;
            }
        }
        // A trailing space left by the truncation point is noise in the moderator's inbox.
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        return out.substring(0, end);
    }

    /** True when {@code target} is a name the message could be sent to. */
    public static boolean validTarget(String target) {
        return target != null && SenderIdentity.validName(target.strip());
    }

    /** The usage line for help output. */
    public static String usage(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "!" : prefix;
        return p + "modcall <what you need> - privately message the configured moderator.";
    }

    /** A one-line description of a call, for a log or a test. */
    public static String describe(Call call) {
        if (call == null || !call.ok()) return "";
        return String.format(Locale.ROOT, "%d message(s) to %s (%d chars)",
            call.commands().size(), call.target(), call.reason().length());
    }
}
