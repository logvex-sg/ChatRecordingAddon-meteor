package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.Placeholders;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.ResponseTemplate;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerAudience;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        // A new rule auto-formats: a labelled command reply becomes a block, prose stays one line.
        TriggerRule fresh = TriggerRule.of("!tps", "TPS 19.9, MSPT 12");
        assertFalse(fresh.embed(), "a new rule is not forced into a block");
        assertTrue(fresh.wantsEmbed("TPS 19.9, MSPT 12"), "a labelled command reply should be a block");
        assertFalse(fresh.wantsEmbed("hello"), "a single value should stay one line");
        assertFalse(fresh.wantsEmbed("Ping 42 ms, good"),
            "a trailing bare value has no second label to align, so it stays one line");

        // A phrase trigger is never boxed automatically, however its reply is punctuated.
        TriggerRule phrase = TriggerRule.of("hello", "Hi there, nice to meet you");
        assertFalse(phrase.wantsEmbed("Hi there, nice to meet you"),
            "prose after a phrase trigger must not become a box");

        // An explicit switch still wins over the automatic choice.
        assertTrue(TriggerRule.decode("hi;reply=hello;embed=true").embed(), "embed=true is honoured");
        assertFalse(TriggerRule.decode("hi;reply=a b, c d;embed=off").wantsEmbed("a b, c d"),
            "embed=off keeps a labelled reply on one line");
        assertTrue(TriggerRule.decode("hi;reply=hello;embed=on").wantsEmbed("hello"),
            "embed=on forces a block even for one value");
        assertEquals(TriggerRule.EmbedMode.AUTO, TriggerRule.decode("hi;reply=hello").embedMode(),
            "an unset embed means auto");
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
    @DisplayName("every shipped rule whispers, except one that runs a server command")
    void shippedRulesUseTheThreeSecondCooldown() {
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            assertEquals(TriggerRule.TRIGGER_COOLDOWN_MS, rule.cooldownMs(),
                "'" + rule.pattern() + "' has the wrong cooldown");
            assertEquals(3_000L, rule.cooldownMs(), "'" + rule.pattern() + "' should be 3000 ms");
            assertTrue(rule.enabled(), "'" + rule.pattern() + "' ships disabled");
            assertTrue(rule.wholeWord(), "'" + rule.pattern() + "' must match whole words");
            assertFalse(rule.caseSensitive(), "'" + rule.pattern() + "' should ignore case");
            if (rule.isCommandReply()) {
                // A server command cannot be whispered: the send path recognises a leading "/" and
                // runs it, so wrapping it in /msg would send the text of the command instead of
                // running it. Such a rule is safe because only the local player may fire it.
                assertEquals(TriggerRule.ReplyMode.CHAT, rule.mode(),
                    "'" + rule.pattern() + "' runs a command but is not delivered as chat");
                assertTrue(rule.allowsCommandFrom(true),
                    "'" + rule.pattern() + "' must run for the local player");
            } else {
                assertEquals(TriggerRule.ReplyMode.PRIVATE, rule.mode(),
                    "'" + rule.pattern() + "' must whisper, so a busy chat does not see the answer");
            }
            assertEquals(0, rule.sessionLimit(), "'" + rule.pattern() + "' ships with a limit");
            assertFalse(rule.sound(), "'" + rule.pattern() + "' should not beep");
            assertFalse(rule.remember(), "'" + rule.pattern() + "' should not fill memory");
            assertEquals("", rule.sender(), "'" + rule.pattern() + "' ships sender-restricted");
        }
    }

    @Test
    @DisplayName("a prefixed shipped rule only shadows a command open to everyone")
    void prefixedRulesOnlyShadowPublicCommands() {
        // A trigger is matched before the command parser, so a prefixed rule takes the place of the
        // command of the same name. Shadowing a restricted command would hand a stranger what the
        // command itself refuses them, so no shipped rule does it: the restricted verbs - !coords,
        // !armor, !held and the rest - are answered by the status commands, which are already gated.
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
    @DisplayName("a shipped rule that shadows a restricted command is refused to a stranger")
    void shadowingRulesAreGatedForStrangers() {
        // Defensive: if a future rule does shadow a restricted command, the runtime must still
        // refuse it to a remote sender. Today no rule does, so the check is vacuous by design
        // rather than by accident.
        List<TriggerRule> shadowing = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            String pattern = rule.pattern().strip();
            if (!pattern.startsWith("!")) continue;
            String verb = pattern.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            String canonical = com.logvex.addon.assistant.core.CommandCatalog.canonical(verb)
                .orElse(null);
            if (canonical == null) continue;
            if (com.logvex.addon.assistant.core.CommandCatalog.defaultLevelFor(canonical)
                    != com.logvex.addon.assistant.core.PermissionLevel.EVERYONE) {
                shadowing.add(rule);
            }
        }
        for (TriggerRule rule : shadowing) {
            AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
            runtime.setTriggers(TriggerSet.of(List.of(rule)));
            runtime.setConfig(AssistantConfig.DEFAULTS.withAudience(TriggerAudience.EVERYONE));
            runtime.setDuplicateFilter(false);

            var stranger = SenderIdentity.unverified("RandomStranger");
            AssistantBrain.Decision remote = runtime.handle(stranger, rule.pattern(), NOW,
                context().stats(), StatusCommands.Settings.DEFAULTS);
            assertNotNull(remote, "'" + rule.pattern() + "' produced no decision for a stranger");
            assertTrue(remote.denied() || !remote.acts(),
                "'" + rule.pattern() + "' answered a stranger with a private figure: "
                    + remote.text());
        }
    }

    @Test
    @DisplayName("every verb a help page advertises is answered by a rule or a command")
    void helpPagesOnlyAdvertiseRealVerbs() {
        // A help page is the one place a user learns the vocabulary, so a stale entry is worse than
        // a missing one: they type it, nothing happens, and the assistant looks broken. Every !verb
        // in the pages is checked against the shipped rules and the status commands.
        java.util.Set<String> answered = new java.util.HashSet<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            for (String phrase : rule.phrases()) {
                String trimmed = phrase.strip();
                if (trimmed.startsWith("!")) {
                    answered.add(trimmed.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        for (String command : com.logvex.addon.stats.StatusCommand.names()) {
            answered.add("!" + command.toLowerCase(java.util.Locale.ROOT));
        }
        // The catalog is the other half of the vocabulary. Without it the pages could not mention
        // !macros or !macro, which are real commands but not status commands.
        for (String command : com.logvex.addon.assistant.core.CommandCatalog.names()) {
            answered.add("!" + command.toLowerCase(java.util.Locale.ROOT));
        }
        for (java.util.Map.Entry<String, String> alias
            : com.logvex.addon.assistant.core.CommandCatalog.aliases().entrySet()) {
            answered.add("!" + alias.getKey().toLowerCase(java.util.Locale.ROOT));
        }

        List<String> advertised = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (!rule.pattern().startsWith("!cmds")) continue;
            for (String word : rule.reply().split("[\\s,]+")) {
                String trimmed = word.strip();
                if (trimmed.startsWith("!")) {
                    advertised.add(trimmed.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        assertFalse(advertised.isEmpty(), "the help pages advertise nothing at all");

        List<String> stale = new ArrayList<>();
        for (String verb : advertised) {
            if (!answered.contains(verb)) stale.add(verb);
        }
        assertTrue(stale.isEmpty(), "a help page advertises verbs nothing answers: " + stale);
    }

    @Test
    @DisplayName("an embed rule uses the same capitalisation for every label")
    void embedLabelsAreCapitalised() {
        // The first word of each clause becomes the row label, so a lowercase word next to a
        // capitalised one reads as a typo rather than a style: "Server ... ping 42 ms". Every
        // labelled row of a shipped embed rule must start with an upper-case letter.
        List<String> inconsistent = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (!rule.wantsEmbed(rule.reply())) continue;
            String expanded = ResponseTemplate.expand(rule.reply(), context());
            // Read the labels off the embed the runtime actually builds, so this checks what a user
            // sees rather than a parallel reimplementation of the split.
            for (ReplyFormat.Field field : rule.toEmbed(expanded, rule.pattern()).fields()) {
                String label = field.label();
                if (label.isEmpty()) continue;
                if (!Character.isUpperCase(label.codePointAt(0))) {
                    inconsistent.add(rule.pattern() + " -> '" + label + "'");
                }
            }
        }
        assertTrue(inconsistent.isEmpty(),
            "an embed row label is not capitalised: " + inconsistent);
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
        runtime.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS
            .withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);

        ServerStats stats = context().stats();
        AssistantRuntime r2 = new AssistantRuntime(new AssistantMemory());
        r2.setTriggers(TriggerSet.of(List.of(rule)));
        r2.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS
            .withAudience(TriggerAudience.EVERYONE));
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
        runtime.setConfig(com.logvex.addon.assistant.core.AssistantConfig.DEFAULTS
            .withAudience(TriggerAudience.EVERYONE));
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
        runtime.setConfig(AssistantConfig.DEFAULTS.withAudience(TriggerAudience.EVERYONE));
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
