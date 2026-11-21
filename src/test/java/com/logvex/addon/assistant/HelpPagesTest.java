package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.HelpPages;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.stats.StatusCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The help pages are generated, so these tests guard the generation rather than the text.
 *
 * <p>The failure they exist for is the one that already happened: {@code !macros} shipped, worked,
 * and was on no page, because the pages were hand-written and nobody remembered to edit them.
 */
class HelpPagesTest {

    private static List<TriggerRule> pages() {
        List<TriggerRule> out = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (rule.pattern().toLowerCase(Locale.ROOT).startsWith("!cmds")) out.add(rule);
        }
        return out;
    }

    private static Set<String> advertised() {
        Set<String> out = new LinkedHashSet<>();
        for (TriggerRule rule : pages()) {
            // Split on the middot and the box characters the pages are rendered with as well as
            // whitespace and commas: a page reads "Server: !tps · !ping", and a verb glued to the
            // separator would otherwise go unseen by this parser and look like a missing command.
            for (String word : rule.reply().split("[\\s,·│┌┐└┘─]+")) {
                if (word.startsWith("!")) out.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    @Test
    @DisplayName("every command the assistant answers appears on some page")
    void everyCommandIsListed() {
        // The property that matters: adding a command changes the pages without editing them.
        Set<String> shown = advertised();
        List<String> missing = new ArrayList<>();
        for (String name : CommandCatalog.names()) {
            if (!shown.contains("!" + name.toLowerCase(Locale.ROOT))) missing.add(name);
        }
        assertTrue(missing.isEmpty(), "commands on no help page: " + missing);
    }

    @Test
    @DisplayName("every status verb appears on some page")
    void everyStatusVerbIsListed() {
        Set<String> shown = advertised();
        List<String> missing = new ArrayList<>();
        for (String name : StatusCommand.names()) {
            if (!shown.contains("!" + name.toLowerCase(Locale.ROOT))) missing.add(name);
        }
        assertTrue(missing.isEmpty(), "status verbs on no help page: " + missing);
    }

    @Test
    @DisplayName("a page never advertises a verb nothing answers")
    void noStaleVerbs() {
        Set<String> answered = new LinkedHashSet<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (rule.pattern().startsWith("!")) answered.add(rule.pattern().toLowerCase(Locale.ROOT));
        }
        for (String name : StatusCommand.names()) {
            answered.add("!" + name.toLowerCase(Locale.ROOT));
        }
        for (String name : CommandCatalog.names()) {
            answered.add("!" + name.toLowerCase(Locale.ROOT));
        }
        for (String alias : CommandCatalog.aliases().keySet()) {
            answered.add("!" + alias.toLowerCase(Locale.ROOT));
        }

        List<String> stale = new ArrayList<>();
        for (String verb : advertised()) {
            if (!answered.contains(verb)) stale.add(verb);
        }
        assertTrue(stale.isEmpty(), "a page advertises verbs nothing answers: " + stale);
    }

    @Test
    @DisplayName("no page is longer than the reply budget")
    void pagesFitTheReplyBudget() {
        for (TriggerRule rule : pages()) {
            assertTrue(rule.reply().length() <= 200,
                rule.pattern() + " is " + rule.reply().length() + " characters");
        }
    }

    @Test
    @DisplayName("every page names the next page, and the last one does not")
    void pagesChain() {
        List<TriggerRule> rules = pages();
        List<TriggerRule> numbered = new ArrayList<>();
        for (TriggerRule rule : rules) {
            if (!rule.pattern().equals("!cmds")) numbered.add(rule);
        }
        for (int i = 0; i < numbered.size(); i++) {
            TriggerRule rule = numbered.get(i);
            boolean last = i == numbered.size() - 1;
            boolean pointsOn = rule.reply().contains("!cmds" + (i + 2));
            assertEquals(!last, pointsOn,
                rule.pattern() + (last ? " is last and must not point on" : " must point to the next"));
        }
    }

    @Test
    @DisplayName("the index names every page and renders as a block")
    void indexListsEveryPage() {
        TriggerRule index = null;
        for (TriggerRule rule : pages()) {
            if (rule.pattern().equals("!cmds")) index = rule;
        }
        assertTrue(index != null, "there is no !cmds index");
        int numbered = pages().size() - 1;
        for (int page = 1; page <= numbered; page++) {
            assertTrue(index.reply().contains("!cmds" + page),
                "the index does not mention !cmds" + page);
        }
        assertTrue(index.wantsEmbed(index.reply()),
            "the index should render as a block, not a sentence");
        List<String> rendered = ReplyFormat.render(index.toEmbed(index.reply(), index.pattern()), 44);
        assertFalse(rendered.isEmpty(), "the index rendered to nothing");
    }

    @Test
    @DisplayName("every name in the curated grouping is a real command")
    void groupingNamesOnlyRealCommands() {
        // A grouped name that does not exist is dropped at runtime, which would quietly shrink a
        // page. Catch it here instead, so the group lists cannot rot. The known set has to mirror
        // build(): trigger rules count too, since !verdict and !biome are answered by a rule rather
        // than by a status command or a catalogue entry.
        Set<String> known = new LinkedHashSet<>(CommandCatalog.names());
        for (String name : StatusCommand.names()) known.add(name);
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (rule.pattern().startsWith("!")) known.add(rule.pattern().substring(1));
        }
        known.add("cmds");

        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : HelpPages.groups().entrySet()) {
            for (String name : group.getValue()) {
                if (!known.contains(name)) unknown.add(group.getKey() + " -> " + name);
            }
        }
        assertTrue(unknown.isEmpty(), "the grouping names commands that do not exist: " + unknown);
    }

    @Test
    @DisplayName("the generated text is stable across calls")
    void generationIsDeterministic() {
        assertEquals(TriggerRule.preconfigured().size(), TriggerRule.preconfigured().size());
        List<String> first = new ArrayList<>();
        for (TriggerRule rule : pages()) first.add(rule.reply());
        List<String> second = new ArrayList<>();
        for (TriggerRule rule : pages()) second.add(rule.reply());
        assertEquals(first, second, "the pages changed between two calls");
    }

    @Test
    @DisplayName("a prefix other than ! is honoured")
    void honoursACustomPrefix() {
        List<HelpPages.Page> pages = HelpPages.build("#", 200, List.of("#tps"));
        assertFalse(pages.isEmpty(), "no pages built for #");
        for (HelpPages.Page page : pages) {
            assertTrue(page.text().contains("#"), page.text() + " lost the prefix");
            assertFalse(page.text().contains("!tps"), page.text() + " kept the default prefix");
        }
    }

    @Test
    @DisplayName("no page loses its verbs to the card line cap")
    void pagesSurviveTheCardCap() {
        // The delivered card is capped at twelve lines. A page whose verbs each claimed a row used
        // to overflow that cap, and the row the cap dropped was the "Next" link, so the reader was
        // stranded on page one with no sign that anything was missing. Rendering the page as it is
        // actually delivered is the only way to see that.
        for (TriggerRule rule : pages()) {
            List<String> lines = ReplyFormat.render(
                rule.toEmbed(rule.reply(), rule.pattern()), 52);
            assertTrue(lines.size() <= 12, rule.pattern() + " renders " + lines.size() + " lines");
            String rendered = String.join("\n", lines);
            assertFalse(rendered.matches("(?s).*\\+\\d+ more.*"),
                rule.pattern() + " dropped a row: " + rendered);
        }
    }
}
