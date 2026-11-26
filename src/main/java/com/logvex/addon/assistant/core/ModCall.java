package com.logvex.addon.assistant.core;

import java.util.Locale;

/**
 * Builds the content of a client-side report: who is reporting, what happened, and which kind.
 *
 * <p>Nothing here sends anything. A report is posted by this client to the user's own webhook as an
 * embed card ({@code ReportEmbed}); it is never a server command and never a chat message. That is
 * the whole point: {@code /report} does not exist on most servers, and {@code /msg} reaches one
 * named player who may be offline.
 *
 * <p>The reason is typed by a player, so it is treated as untrusted: a newline would become a
 * second line in a message, and the text is capped so one report cannot become a wall of text in
 * the channel. Everything here is pure string work, so the rules can be tested without a game.
 */
public final class ModCall {

    /** Longest reason a report may carry, in characters. */
    public static final int MAX_REASON_LENGTH = 180;

    /** The report kind that means "a player asked a moderator for help". */
    public static final String KIND_MOD = "mod";

    /** The report kind that means "a player reported someone or something". */
    public static final String KIND_REPORT = "report";

    private ModCall() {
    }

    /** Why a report could not be built, or {@link #NONE} when it could. */
    public enum Problem {
        NONE(""),
        /** No reason was given, so there is nothing to report. */
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

    /**
     * One built report, ready to hand to the webhook.
     *
     * @param reporter who filed it, as the client knows them
     * @param reason   the sanitised text
     * @param kind     {@link #KIND_MOD} or {@link #KIND_REPORT}
     * @param problem  {@link Problem#NONE} when the report may be posted
     */
    public record Report(String reporter, String reason, String kind, Problem problem) {
        public Report {
            if (reporter == null) reporter = "";
            if (reason == null) reason = "";
            if (kind == null || kind.isBlank()) kind = KIND_MOD;
            if (problem == null) problem = Problem.NO_REASON;
        }

        /** True when the report is complete and may be posted. */
        public boolean ok() {
            return problem == Problem.NONE;
        }

        /** A report that cannot be posted, carrying the reason it cannot. */
        public static Report failed(Problem problem) {
            return new Report("", "", KIND_MOD, problem);
        }
    }

    /** Builds a moderator request, or explains why it cannot be built. */
    public static Report build(String reporter, String reason, String prefix) {
        return build(reporter, reason, prefix, KIND_MOD);
    }

    /**
     * Builds a report of {@code kind}, or explains why it cannot be built.
     *
     * @param reporter who filed it; blank is replaced with {@code someone} downstream
     * @param reason   what they said; untrusted
     * @param prefix   the command prefix, used only to word the usage hint
     * @param kind     {@link #KIND_MOD} or {@link #KIND_REPORT}
     */
    public static Report build(String reporter, String reason, String prefix, String kind) {
        String said = reason == null ? "" : reason.strip();
        if (said.isEmpty()) return Report.failed(Problem.NO_REASON);

        String clean = sanitise(said);
        if (clean.isEmpty()) return Report.failed(Problem.EMPTY_REASON);

        String who = reporter == null ? "" : reporter.strip();
        return new Report(who, clean, kind, Problem.NONE);
    }

    /**
     * Makes {@code text} safe to place in a report card.
     *
     * <p>Every control character is replaced with a space, which collapses newlines, and runs of
     * whitespace are collapsed. The result is capped at {@link #MAX_REASON_LENGTH}.
     */
    public static String sanitise(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_REASON_LENGTH));
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length() && out.length() < MAX_REASON_LENGTH; i++) {
            char c = text.charAt(i);
            // A section sign starts a chat formatting code: the sign and the letter after it are
            // one unit. Dropping only the sign would leave the bare letter behind, so the whole
            // code is skipped and the report stays plain text.
            if (c == '\u00a7') {
                if (i + 1 < text.length()) i++;
                continue;
            }
            boolean space = Character.isWhitespace(c) || Character.isISOControl(c);
            if (space) {
                if (out.length() > 0 && !lastWasSpace) out.append(' ');
                lastWasSpace = true;
            } else {
                out.append(c);
                lastWasSpace = false;
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        return out.substring(0, end);
    }

    /** The usage line for help output. */
    public static String usage(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "!" : prefix;
        return p + "modcall <what you need> - file a report to your reports channel.";
    }

    /** A one-line description of a report, for a log or a test. */
    public static String describe(Report report) {
        if (report == null || !report.ok()) return "";
        return String.format(Locale.ROOT, "%s report from %s (%d chars)",
            report.kind(), report.reporter(), report.reason().length());
    }
}
