package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@code !cmds1}, {@code !cmds2}... help pages from the live command tables.
 *
 * <p>The pages used to be hand-written strings in {@link TriggerRule}, which meant a new command
 * stayed invisible until somebody remembered to edit the text. That is exactly what happened to
 * {@code !macros}: it shipped, worked, and appeared on no page. Generating the pages removes the
 * step that gets forgotten.
 *
 * <p>The grouping is still curated, because {@code !tps} belongs next to {@code !ping} and no
 * automatic rule knows that. What is automatic is completeness: any command that no group claims is
 * appended to a final "More" page, so a command can be ungrouped but never unlisted. Adding a
 * command therefore changes the pages on its own.
 */
public final class HelpPages {

    /**
     * The curated grouping. Order is the page order, and a page's label is its own heading.
     *
     * <p>A name listed here appears on that page even if the command table does not know it; the
     * guard test rejects that, so the lists stay honest.
     */
    private static final Map<String, List<String>> GROUPS = buildGroups();

    private HelpPages() {
    }

    private static Map<String, List<String>> buildGroups() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("Server", List.of("tps", "ping", "players", "uptime", "server", "verdict",
            "status", "who", "stats"));
        out.put("World", List.of("world", "time", "day", "weather", "biome", "dimension",
            "difficulty", "serverinfo"));
        out.put("Client", List.of("fps", "ram", "memory", "session", "clientinfo", "overview",
            "timestamp", "date"));
        out.put("You", List.of("whoami", "welcome", "vitals", "hunger", "facing", "offhand",
            "environment"));
        out.put("Commands", List.of("health", "speed", "gamemode", "coords", "armor", "held",
            "level", "absorption"));
        out.put("More", List.of("location", "target", "effects", "network", "help", "cmds",
            "macros", "macrohelp", "share"));
        // Grouped rather than left to the automatic pages: an "A-G" heading tells a reader nothing
        // about what is on the page, and these were the commands that landed there.
        out.put("Assistant", List.of("about", "activity", "summary", "history", "triggers",
            "player", "memories"));
        out.put("Memory", List.of("fact", "facts", "remember", "forget", "forgetme", "faq"));
        out.put("Reports", List.of("mod", "modcall", "report", "reports"));
        out.put("Social", List.of("hello", "gg"));
        return out;
    }

    /**
     * One rendered page: the reply text, the page number it answers to, and how many pages exist.
     *
     * @param number the {@code N} in {@code !cmdsN}, starting at 1
     * @param text   the whole reply, already within the reply-length budget
     */
    public record Page(int number, String text) {
    }

    /**
     * Every page, in order, built from the commands the tables actually answer.
     *
     * @param prefix        the configured command prefix, normally {@code !}
     * @param budget        the widest reply the assistant may send
     * @param triggerVerbs  the shipped command-shaped trigger patterns; passed in rather than read
     *                      from {@link TriggerRule#preconfigured()} because that method is what
     *                      calls this one
     */
    public static List<Page> build(String prefix, int budget, Collection<String> triggerVerbs) {
        String sigil = prefix == null || prefix.isBlank() ? "!" : prefix;
        Set<String> known = knownVerbs(sigil, triggerVerbs);

        List<Page> pages = new ArrayList<>();
        Set<String> shown = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> group : GROUPS.entrySet()) {
            List<String> verbs = new ArrayList<>();
            for (String name : group.getValue()) {
                String verb = sigil + name;
                // A grouped name the tables do not answer is dropped rather than printed. The guard
                // test fails on the same condition, so this only hides the damage at runtime; it
                // cannot hide it from the build.
                if (known.contains(verb) && shown.add(verb)) verbs.add(verb);
            }
            if (!verbs.isEmpty()) {
                pages.add(new Page(pages.size() + 1, line(group.getKey(), verbs, budget)));
            }
        }

        // Anything the groups did not claim. Sorted so the page does not reorder itself between
        // runs, which would make the text depend on registration order.
        List<String> ungrouped = new ArrayList<>();
        for (String verb : known) {
            if (!shown.contains(verb) && !isHelpVerb(verb, sigil)) ungrouped.add(verb);
        }
        ungrouped.sort(String.CASE_INSENSITIVE_ORDER);
        int perPage = 8;
        for (int start = 0; start < ungrouped.size(); start += perPage) {
            List<String> slice = ungrouped.subList(start, Math.min(start + perPage, ungrouped.size()));
            // Headed by the letter range it covers rather than "Also" again: the label is one token
            // so the embed column stays aligned, and "A-G" tells a reader more than a repeat would.
            String heading = letterRange(slice);
            pages.add(new Page(pages.size() + 1, line(heading, new ArrayList<>(slice), budget)));
        }
        return List.copyOf(pages);
    }

    /** The first letter of the first verb to the first letter of the last, as {@code A-G}. */
    private static String letterRange(List<String> verbs) {
        if (verbs.isEmpty()) return "Also";
        String first = firstLetter(verbs.get(0));
        String last = firstLetter(verbs.get(verbs.size() - 1));
        return first.equals(last) ? first : first + "-" + last;
    }

    private static String firstLetter(String verb) {
        int at = 0;
        while (at < verb.length() && !Character.isLetterOrDigit(verb.charAt(at))) at++;
        if (at >= verb.length()) return "?";
        return String.valueOf(Character.toUpperCase(verb.charAt(at)));
    }

    /**
     * The pages as ready-to-add rules, numbered {@code !cmds1..N}.
     *
     * <p>Every page also points at the next one, so a reader who finds any page can walk the rest
     * without knowing how many there are. The rule shape matches the other shipped command rules:
     * case-insensitive, whole-word, whispered, at the shipped cooldown.
     */
    public static List<TriggerRule> rules(String prefix, int budget, Collection<String> triggerVerbs) {
        String sigil = prefix == null || prefix.isBlank() ? "!" : prefix;
        List<Page> pages = build(sigil, budget, triggerVerbs);
        List<TriggerRule> out = new ArrayList<>();
        out.add(indexRule(sigil, pages));
        for (int i = 0; i < pages.size(); i++) {
            String text = pages.get(i).text();
            int next = i + 2;
            if (next <= pages.size()) {
                text = text + ", Next: " + sigil + "cmds" + next;
            }
            out.add(new TriggerRule(sigil + "cmds" + (i + 1), false, true, "", text,
                TriggerRule.ReplyMode.PRIVATE, TriggerRule.TRIGGER_COOLDOWN_MS, 0, false, false,
                true, "", "", TriggerRule.DEFAULT_WHISPER));
        }
        return List.copyOf(out);
    }

    /**
     * The {@code !cmds} index: one labelled row per page, so the whole set is visible at once.
     *
     * <p>Written as labelled clauses on purpose. That is what lets the rule render as a block
     * instead of a comma-separated sentence, and the block is the readable form when the answer is a
     * table of pages rather than a list of verbs.
     */
    private static TriggerRule indexRule(String sigil, List<Page> pages) {
        StringBuilder text = new StringBuilder();
        for (Page page : pages) {
            String heading = page.text();
            int colon = heading.indexOf(':');
            String label = colon > 0 ? heading.substring(0, colon) : "Page " + page.number();
            if (text.length() > 0) text.append(", ");
            text.append(label).append(": ").append(sigil).append("cmds").append(page.number());
        }
        return new TriggerRule(sigil + "cmds", false, true, "", text.toString(),
            TriggerRule.ReplyMode.PRIVATE, TriggerRule.TRIGGER_COOLDOWN_MS, 0, false, false,
            true, "", "", TriggerRule.DEFAULT_WHISPER);
    }

    /** The curated grouping, exposed so a test can check every name in it is real. */
    public static Map<String, List<String>> groups() {
        return GROUPS;
    }

    /** True for the page verbs themselves, which would otherwise pad the automatic page. */
    private static boolean isHelpVerb(String verb, String sigil) {
        String bare = verb.substring(sigil.length()).toLowerCase(Locale.ROOT);
        return bare.startsWith("cmds") || bare.equals("help");
    }

    /**
     * Every verb the assistant answers: the shipped command-shaped rules, the status commands and
     * the catalogue.
     */
    static Set<String> knownVerbs(String sigil, Collection<String> triggerVerbs) {
        Set<String> out = new LinkedHashSet<>();
        for (String pattern : triggerVerbs) {
            if (pattern.startsWith(sigil)) out.add(pattern);
        }
        for (String name : com.logvex.addon.stats.StatusCommand.names()) {
            out.add(sigil + name);
        }
        for (String name : CommandCatalog.names()) {
            out.add(sigil + name);
        }
        // The index is generated by this class, so no other table knows it. Registering it here is
        // what lets it be grouped with !help instead of falling through to a catch-all page.
        out.add(sigil + "cmds");
        return out;
    }

    /**
     * One page's text: {@code "Heading: !a, !b, !c"}.
     *
     * <p>Verbs are added while they fit. A page that would overflow is cut, and the cut is reported
     * by the guard test rather than silently losing a command, so the grouping is kept small enough
     * to fit in one message.
     */
    private static String line(String heading, List<String> verbs, int budget) {
        StringBuilder text = new StringBuilder(heading).append(": ");
        for (int i = 0; i < verbs.size(); i++) {
            String piece = (i == 0 ? "" : ", ") + verbs.get(i);
            if (text.length() + piece.length() > budget) break;
            text.append(piece);
        }
        return text.toString();
    }
}
