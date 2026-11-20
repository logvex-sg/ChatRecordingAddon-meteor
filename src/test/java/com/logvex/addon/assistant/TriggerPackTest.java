package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.Placeholders;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.ResponseTemplate;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped trigger table, checked against the three properties that make it safe to ship.
 *
 * <p>Each of these guards corresponds to a way the table has actually been wrong: two patterns that
 * overlap so the winner depends on list order, a template that appends its own unit to a value that
 * already carries one ({@code 42 msms}), and a reply that reads the operator's own position while
 * being answerable by anyone.
 */
class TriggerPackTest {

    private static final long NOW = 1_700_000_000_000L;

    private static ResponseTemplate.Context context() {
        ServerStats stats = new ServerStats(
            true, "mc.example.net", "mc.example.net:25565", false,
            19.8f, 42, 12, 40, 3_600_000L,
            "minecraft:overworld", "world", "plains", 6_000L, 1L,
            "clear", 144, 512L, 4096L,
            List.of("Notch"), null);
        return new ResponseTemplate.Context("Notch", "hello", "mc.example.net",
            new AssistantMemory(), NOW, stats, "Assistant", "!");
    }

    @Test
    @DisplayName("no earlier shipped rule swallows a later one")
    void earlierRulesDoNotSwallowLaterOnes() {
        // First match wins and matching is whole-word substring, so an earlier broad phrase makes a
        // later longer one unreachable: a rule for "hello" answers "hello there" before the rule
        // written for "hello there" is ever consulted. This is the real overlap condition - a plain
        // substring check would flag harmless cases such as "ty" inside "difficulty".
        List<TriggerRule> rules = TriggerRule.preconfigured();
        List<String> clashes = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                TriggerRule earlier = rules.get(i);
                TriggerRule later = rules.get(j);
                for (String phrase : later.phrases()) {
                    if (!TriggerSet.matches(earlier, phrase)) continue;
                    clashes.add("'" + earlier.pattern() + "' answers '" + phrase
                        + "' before '" + later.pattern() + "' can");
                }
            }
        }
        assertTrue(clashes.isEmpty(), "a shipped rule is unreachable: " + clashes);
    }

    @Test
    @DisplayName("every shipped rule is reachable by typing its own pattern")
    void everyShippedRuleMatchesItsOwnPattern() {
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured());
        List<String> unreachable = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            for (String phrase : rule.phrases()) {
                TriggerRule found = set.firstMatch("Steve", phrase);
                if (found == null || !found.pattern().equals(rule.pattern())) {
                    unreachable.add("'" + phrase + "' resolves to "
                        + (found == null ? "nothing" : "'" + found.pattern() + "'")
                        + " instead of '" + rule.pattern() + "'");
                }
            }
        }
        assertTrue(unreachable.isEmpty(), "a shipped phrase is unreachable: " + unreachable);
    }

    @Test
    @DisplayName("an embed rule round-trips through the stored form")
    void embedSurvivesEncodeAndDecode() {
        TriggerRule embed = TriggerRule.preconfigured().stream()
            .filter(TriggerRule::embed).findFirst().orElseThrow();
        TriggerRule back = TriggerRule.decode(embed.encode());
        assertTrue(back.embed(), "'" + embed.pattern() + "' lost its embed flag on a round trip");
        // The default must stay the plain form, or every hand-written rule would start as a block.
        assertFalse(TriggerRule.of("hi", "hello").embed(), "a new rule should be one line");
        assertFalse(TriggerRule.decode("hi;reply=hello").embed(), "an unset embed is off");
        assertTrue(TriggerRule.decode("hi;reply=hello;embed=true").embed(), "embed=true is honoured");
    }

    @Test
    @DisplayName("every embed rule renders as a labelled block, never a stray line")
    void embedRulesRenderAsBlocks() {
        ResponseTemplate.Context ctx = context();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (!rule.embed()) continue;
            String expanded = ResponseTemplate.expand(rule.reply(), ctx);
            List<String> lines = ReplyFormat.render(rule.toEmbed(expanded, rule.pattern()), 40);
            assertTrue(lines.size() >= 3, "'" + rule.pattern() + "' rendered " + lines);
            // A box opens and closes on the frame characters, so a half-rendered block is obvious.
            assertTrue(lines.get(0).startsWith("┌"), "'" + rule.pattern() + "' has no top border");
            assertTrue(lines.get(lines.size() - 1).startsWith("└"),
                "'" + rule.pattern() + "' has no bottom border");
            for (String line : lines) {
                assertTrue(line.length() <= 40, "'" + rule.pattern() + "' overflows: " + line);
                assertFalse(line.contains("{"), "'" + rule.pattern() + "' left a token: " + line);
            }
        }
    }

    @Test
    @DisplayName("no shipped reply repeats a unit the placeholder already carries")
    void noDoubleUnits() {
        // {ping} resolves to "42 ms" and {mspt} to "50.5", so "{ping}ms" prints "42 msms". Every
        // reply is expanded against a full context and the result is checked for the doubled forms.
        ResponseTemplate.Context ctx = context();
        List<String> broken = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            String text = ResponseTemplate.expand(rule.reply(), ctx);
            for (String doubled : List.of("msms", "ms ms", "tps tps", "fps fps", "%%")) {
                if (text.contains(doubled)) broken.add(rule.pattern() + " -> " + text);
            }
        }
        assertTrue(broken.isEmpty(), "a shipped reply doubles a unit: " + broken);
    }

    @Test
    @DisplayName("every shipped reply expands with no placeholder left behind")
    void repliesExpandFully() {
        ResponseTemplate.Context ctx = context();
        List<String> broken = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            String text = ResponseTemplate.expand(rule.reply(), ctx);
            if (text.contains("{") || text.contains("}")) {
                broken.add(rule.pattern() + " -> " + text);
            }
            assertFalse(text.isBlank(), rule.pattern() + " expands to nothing");
        }
        assertTrue(broken.isEmpty(), "a shipped reply leaves a token unexpanded: " + broken);
    }

    @Test
    @DisplayName("every shipped rule carries the three-second cooldown and full field values")
    void shippedRulesUseTheThreeSecondCooldown() {
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            assertEquals(TriggerRule.TRIGGER_COOLDOWN_MS, rule.cooldownMs(),
                "'" + rule.pattern() + "' has the wrong cooldown");
            assertEquals(3_000L, rule.cooldownMs(), "'" + rule.pattern() + "' should be 3000 ms");
            assertTrue(rule.enabled(), "'" + rule.pattern() + "' ships disabled");
            assertTrue(rule.wholeWord(), "'" + rule.pattern() + "' must match whole words");
            assertFalse(rule.caseSensitive(), "'" + rule.pattern() + "' should ignore case");
            assertEquals(TriggerRule.ReplyMode.PRIVATE, rule.mode(),
                "'" + rule.pattern() + "' must whisper, so a busy chat does not see the answer");
            assertEquals(0, rule.sessionLimit(), "'" + rule.pattern() + "' ships with a limit");
            assertFalse(rule.sound(), "'" + rule.pattern() + "' should not beep");
            assertFalse(rule.remember(), "'" + rule.pattern() + "' should not fill memory");
            assertEquals("", rule.sender(), "'" + rule.pattern() + "' ships sender-restricted");
        }
    }

    @Test
    @DisplayName("a prefixed shipped rule only shadows a command open to everyone")
    void prefixedRulesOnlyShadowPublicCommands() {
        List<String> clashes = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            String pattern = rule.pattern().strip();
            if (!pattern.startsWith("!")) continue;
            String verb = pattern.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            String canonical = com.logvex.addon.assistant.core.CommandCatalog.canonical(verb)
                .orElse(null);
            if (canonical == null) continue;
            if (com.logvex.addon.assistant.core.CommandCatalog.defaultLevelFor(canonical)
                    != com.logvex.addon.assistant.core.PermissionLevel.EVERYONE) {
                clashes.add(pattern + " shadows the restricted command !" + canonical);
            }
        }
        assertTrue(clashes.isEmpty(), "a shipped trigger exposes a restricted command: "
            + clashes);
    }

    @Test
    @DisplayName("the table covers greetings, live figures and command shapes")
    void tableCoversTheUsefulCategories() {
        List<String> patterns = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            patterns.add(rule.pattern().toLowerCase(Locale.ROOT));
        }
        assertTrue(patterns.size() >= 70, "expected a large table, got " + patterns.size());
        assertTrue(patterns.contains("hello"), "no greeting");
        assertTrue(patterns.contains("what is the tps"), "no prose tps question");
        assertTrue(patterns.contains("where am i"), "no position question");
        assertTrue(patterns.contains("!tps"), "no command-shaped tps");
    }

    @Test
    @DisplayName("a reply naming the operator's position is refused to a remote sender")
    void privateFiguresAreRefusedToStrangers() {
        // A trigger is answered for whoever typed it. "where am i" must not hand a stranger the
        // operator's coordinates, which is exactly what the command path already refuses.
        TriggerRule rule = TriggerRule.of("where am i", "{coords} in {biome}");
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(List.of(rule)));
        runtime.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        ServerStats stats = context().stats();
        AssistantRuntime r2 = new AssistantRuntime(new AssistantMemory());
        r2.setTriggers(TriggerSet.of(List.of(rule)));
        r2.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS);
        r2.setDuplicateFilter(false);

        com.logvex.addon.assistant.core.AssistantBrain.Decision stranger =
            r2.handle(SenderIdentity.unverified("Steve"), "where am i", NOW, stats, null);
        assertFalse(stranger.acts() && stranger.text().contains("0, 0, 0"),
            "a stranger was shown the operator's coordinates: " + stranger.text());
        assertTrue(stranger.denied(), "the refusal should be marked denied");

        com.logvex.addon.assistant.core.AssistantBrain.Decision owner =
            runtime.handle(SenderIdentity.local("Notch", null), "where am i", NOW, stats, null);
        assertTrue(owner.acts(), "the operator must still get their own position");
        assertTrue(owner.text().contains("0, 0, 0"), owner.text());
    }

    @Test
    @DisplayName("a reply of server-wide figures still answers a stranger")
    void publicFiguresAnswerAnyone() {
        TriggerRule rule = TriggerRule.of("what is the tps", "TPS {tps}, {mspt} ms per tick");
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(List.of(rule)));
        runtime.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        com.logvex.addon.assistant.core.AssistantBrain.Decision stranger = runtime.handle(
            SenderIdentity.unverified("Steve"), "what is the tps", NOW, context().stats(), null);
        assertTrue(stranger.acts(), "server-wide figures are public and must answer anyone");
        assertTrue(stranger.text().contains("19.8"), stranger.text());
    }

    @Test
    @DisplayName("the private-figure list matches what the shipped templates actually use")
    void privateFigureDetectionCoversShippedTemplates() {
        assertTrue(Placeholders.mentionsPrivateFigure("{coords} in the open"));
        assertTrue(Placeholders.mentionsPrivateFigure("Health {health}"));
        assertTrue(Placeholders.mentionsPrivateFigure("{fact:base}"));
        assertTrue(Placeholders.mentionsPrivateFigure("{factcount} facts"));
        assertFalse(Placeholders.mentionsPrivateFigure("TPS {tps}, ping {ping}"));
        assertFalse(Placeholders.mentionsPrivateFigure("Hello {player}!"));
        assertFalse(Placeholders.mentionsPrivateFigure("Server {server}, {playercount} players"));
        assertFalse(Placeholders.mentionsPrivateFigure("Biome {biome}, {dimension}"));
    }

    @Test
    @DisplayName("a private figure is answered to the operator and refused to a stranger")
    void privateFiguresReachOnlyTheOperator() {
        // The whole point of the check: the same line, two speakers, two different answers. Asking
        // the helper alone would not prove the runtime consults it, or that it consults it on the
        // trigger path rather than only the command path.
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);

        SenderIdentity stranger = SenderIdentity.unverified("Stranger");
        SenderIdentity operator = SenderIdentity.local("You", java.util.UUID.randomUUID());
        long now = NOW;

        for (String question : List.of("where am i", "what is my health", "what is my armour")) {
            AssistantBrain.Decision remote =
                runtime.handle(stranger, question, now, context().stats(), null);
            assertTrue(remote.denied(), "'" + question + "' was answered to a stranger: "
                + remote.text());
            assertFalse(remote.text().contains("{"), question + " leaked a raw token");

            AssistantBrain.Decision own = runtime.handle(operator, question, now + 60_000L,
                context().stats(), null);
            assertFalse(own.denied(), "'" + question + "' was refused to the operator");
            assertFalse(own.text().isBlank(), question + " gave the operator nothing");
        }

        // A server-wide figure is not private, so anyone may ask it.
        AssistantBrain.Decision publicAsk =
            runtime.handle(stranger, "what is the tps", now + 120_000L, context().stats(), null);
        assertFalse(publicAsk.denied(), "a server figure must be answerable by anyone");
    }
}
