package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.ChatMacro;
import com.logvex.addon.assistant.core.MacroSet;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reply that does not fit the delivery cap must lose whole rows and say so, never stop mid-line.
 *
 * <p>Two bugs lived here. A LIST-style reply was cut with {@code subList}, so {@code !help} ended on
 * a half-sentence. And a page could be sized for more rows than the cap allowed, so a command was
 * reported as "+1 more" and never shown on any page. Both are silent, which is why they need a test.
 */
class ReplyTruncationTest {

    private static final int CAP = 12;

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        runtime.brain().setMacros(MacroSet.of(ChatMacro.defaults(), "!"));
        return runtime;
    }

    private static List<String> capped(AssistantRuntime runtime, String command) {
        AssistantBrain.Decision decision = runtime.handle("Notch", command, 1_000L);
        assertTrue(decision.hasEmbed(), command + " should answer with a block");
        return decision.linesCapped(ReplyFormat.DEFAULT_WIDTH, null, CAP);
    }

    @Test
    @DisplayName("a cut list keeps whole rows and counts the rest instead of ending mid-sentence")
    void listCapKeepsWholeRows() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!help", 1_000L);
        // A cap far below the full list, so the cut path is the one under test.
        List<String> lines = decision.linesCapped(ReplyFormat.DEFAULT_WIDTH, null, 6);

        assertTrue(lines.size() <= 6, "the cap must hold: " + lines.size());
        String last = lines.get(lines.size() - 1);
        assertTrue(last.contains("more"), "the omitted rows are reported: " + last);
        for (String line : lines) {
            assertFalse(line.isBlank(), "no row is cut into a blank fragment: " + lines);
        }
    }

    @Test
    @DisplayName("a box that does not fit closes its frame and reports the remainder")
    void boxCapClosesTheFrame() {
        List<String> lines = capped(runtime(), "!macros");

        assertTrue(lines.size() <= CAP, "the cap must hold: " + lines.size());
        assertTrue(lines.get(lines.size() - 1).startsWith("└"), "the box is closed: " + lines);
    }

    @Test
    @DisplayName("!macros is paged, so every macro is reachable")
    void everyMacroIsReachable() {
        AssistantRuntime runtime = runtime();
        int total = runtime.brain().macros().enabled().size();
        assertTrue(total > AssistantBrain.MACROS_PAGE_SIZE, "the shipped set spans several pages");

        StringBuilder seen = new StringBuilder();
        for (int page = 1; page <= 20; page++) {
            AssistantBrain.Decision decision = runtime.handle("Notch", "!macros " + page, 1_000L);
            if (!decision.hasEmbed()) break;
            for (ReplyFormat.Field field : decision.embed().fields()) seen.append(field.label()).append('\n');
            if (decision.embed().footer().contains("of " + page + " -") || decision.embed().footer()
                .startsWith("Page " + page + " of " + page + " ")) break;
        }
        for (ChatMacro macro : runtime.brain().macros().enabled()) {
            assertTrue(seen.toString().contains(macro.usageLine()),
                "macro " + macro.shortcut() + " appears on no page");
        }
    }

    @Test
    @DisplayName("a page number past the end wraps to the last page rather than showing nothing")
    void pastTheEndShowsTheLastPage() {
        AssistantBrain.Decision decision = runtime().handle("Notch", "!macros 99", 1_000L);

        assertTrue(decision.hasEmbed());
        assertFalse(decision.embed().fields().isEmpty(), "an out-of-range page must not be empty");
    }

    @Test
    @DisplayName("every help page fits the cap, so no command is advertised and then withheld")
    void everyHelpPageFits() {
        AssistantRuntime runtime = runtime();
        SenderIdentity who = SenderIdentity.local("Me", null);
        for (int page = 1; page <= 40; page++) {
            ReplyFormat.Embed embed = runtime.brain().helpEmbed(who, AssistantConfig.DEFAULTS, null,
                null, page);
            if (embed.fields().isEmpty()) break;
            List<String> lines = ReplyFormat.renderCapped(embed, ReplyFormat.DEFAULT_WIDTH, CAP);
            assertTrue(lines.size() <= CAP, "page " + page + " needs " + lines.size() + " lines");
            String last = lines.get(lines.size() - 1);
            assertFalse(last.contains("more"),
                "page " + page + " drops a command: " + last);
        }
    }

    @Test
    @DisplayName("the last page wraps back to the first, so paging never dead-ends")
    void lastPageWraps() {
        AssistantRuntime runtime = runtime();
        AssistantBrain.Decision first = runtime.handle("Notch", "!macros 1", 1_000L);
        String footer = first.embed().footer();
        int pages = Integer.parseInt(footer.replaceAll(".*of (\\d+) -.*", "$1"));

        AssistantBrain.Decision last = runtime.handle("Notch", "!macros " + pages, 1_000L);
        assertTrue(last.embed().footer().contains("!macros 1 for the next"),
            "the last page points back to the first: " + last.embed().footer());
    }
}