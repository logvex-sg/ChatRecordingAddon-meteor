package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.PermissionLevel;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Private replies, the shipped command-shaped rules, and the {@code !triggers} listing.
 *
 * <p>The rules here are the behaviours a chat full of players depends on: an answer that only the
 * asker sees, a stranger unable to make the client run a server command, and a shipped table that
 * never shadows a real command.
 */
class PrivateReplyAndTriggerListTest {

    private static final long NOW = 1_000_000L;

    // ---------------------------------------------------------------- private delivery

    @Test
    @DisplayName("a private rule is delivered to the speaker, not spoken in public")
    void privateRuleIsDeliveredPrivately() {
        TriggerRule rule = new TriggerRule("!lag", false, true, "", "TPS is {tps}.",
            TriggerRule.ReplyMode.PRIVATE, 0, 0, false, false, true, "", "", "msg");
        AssistantBrain.Decision decision =
            new AssistantBrain(new AssistantMemory()).trigger(rule, "Steve", "!lag", AssistantConfig.DEFAULTS, NOW);

        assertEquals(AssistantBrain.Delivery.PRIVATE, decision.delivery(),
            "a private rule must not fall back to public chat");
    }

    @Test
    @DisplayName("the whisper verb defaults to msg and can be changed per rule")
    void whisperVerbIsConfigurable() {
        TriggerRule plain = TriggerRule.decode("!lag;mode=private");
        assertNotNull(plain);
        assertEquals("msg", plain.whisperCommand(), "msg is the default verb");

        TriggerRule tell = TriggerRule.decode("!lag;mode=private;whisper=tell");
        assertNotNull(tell);
        assertEquals("tell", tell.whisperCommand(), "a rule may name its own verb");
        assertEquals(TriggerRule.ReplyMode.PRIVATE, tell.mode());
    }

    @Test
    @DisplayName("the whisper verb survives an encode/decode round trip")
    void whisperSurvivesRoundTrip() {
        TriggerRule original = new TriggerRule("!lag", false, true, "", "TPS is {tps}.",
            TriggerRule.ReplyMode.PRIVATE, 500, 2, true, true, true, "", "", "w");
        TriggerRule decoded = TriggerRule.decode(original.encode());
        assertNotNull(decoded);
        assertEquals("w", decoded.whisperCommand());
        assertEquals(TriggerRule.ReplyMode.PRIVATE, decoded.mode());
        assertEquals(500, decoded.cooldownMs());
        assertEquals(2, decoded.sessionLimit());
    }

    @Test
    @DisplayName("a rule written before private replies still loads, with the default verb")
    void oldRuleStillLoads() {
        // The exact 13-field shape a previous build stored, with no whisper key.
        TriggerRule decoded = TriggerRule.decode(
            "hello;case=false;whole=true;sender=;reply=Hi;mode=chat;cooldown=1000;limit=0"
                + ";sound=false;remember=false;enabled=true;aliases=;pool=");
        assertNotNull(decoded);
        assertEquals("msg", decoded.whisperCommand());
        assertEquals(TriggerRule.ReplyMode.CHAT, decoded.mode());
    }

    // ---------------------------------------------------------------- command replies

    @Test
    @DisplayName("a reply beginning with a slash is recognised as a command, and its verb extracted")
    void commandRepliesAreClassified() {
        TriggerRule accept = TriggerRule.decode("!tpaccept;reply=/tpaccept");
        assertNotNull(accept);
        assertTrue(accept.isCommandReply());
        assertEquals("tpaccept", accept.commandVerb());

        TriggerRule chat = TriggerRule.decode("hello;reply=Hi there");
        assertNotNull(chat);
        assertFalse(chat.isCommandReply());
        assertNull(chat.commandVerb());
    }

    @Test
    @DisplayName("only the local player may fire a state-changing command reply")
    void stateChangingCommandIsLocalOnly() {
        TriggerRule accept = TriggerRule.decode("!tpaccept;reply=/tpaccept");
        assertNotNull(accept);
        assertFalse(accept.allowsCommandFrom(false),
            "a stranger must not be able to make the client run /tpaccept");
        assertTrue(accept.allowsCommandFrom(true), "the local player may run it");
    }

    @Test
    @DisplayName("a message-carrying command reply is safe for anyone to fire")
    void communicationCommandIsOpenToEveryone() {
        TriggerRule whisper = TriggerRule.decode("!pingme;reply=/msg {player} hi");
        assertNotNull(whisper);
        assertTrue(whisper.isSafeCommandReply(),
            "a reply that only carries a message cannot change the world");
        assertTrue(whisper.allowsCommandFrom(false));
    }

    @Test
    @DisplayName("plain text replies are open to everyone")
    void textReplyIsOpenToEveryone() {
        TriggerRule hello = TriggerRule.decode("hello;reply=Hi {player}");
        assertNotNull(hello);
        assertTrue(hello.allowsCommandFrom(false));
        assertTrue(hello.allowsCommandFrom(true));
    }

    // ---------------------------------------------------------------- shipped table

    @Test
    @DisplayName("a shipped rule never shadows a restricted command")
    void shippedRulesNeverShadowARestrictedCommand() {
        // A trigger is matched before the command parser, so a prefixed rule does replace the
        // same-named command. That is only safe when the command is already open to everyone;
        // shadowing a LOCAL_ONLY command would either hide it from its owner or expose it.
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            String pattern = rule.pattern().strip();
            if (!pattern.startsWith("!")) continue;
            String word = pattern.substring(1).strip().toLowerCase(java.util.Locale.ROOT);
            if (word.isEmpty()) continue;
            String canonical = CommandCatalog.canonical(word).orElse(null);
            if (canonical == null) continue;
            assertEquals(PermissionLevel.EVERYONE, CommandCatalog.defaultLevelFor(canonical),
                "'" + pattern + "' shadows the " + canonical + " command");
        }
    }

    @Test
    @DisplayName("the shipped table answers command-shaped requests in public chat")
    void shippedCommandRulesArePublic() {
        List<TriggerRule> commandShaped = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (rule.pattern().startsWith("!")) commandShaped.add(rule);
        }
        assertFalse(commandShaped.isEmpty(), "expected some command-shaped rules");
        for (TriggerRule rule : commandShaped) {
            assertEquals(TriggerRule.ReplyMode.CHAT, rule.mode(),
                "'" + rule.pattern() + "' is public: everyone may ask and everyone may read it");
            assertTrue(rule.wholeWord(), "'" + rule.pattern() + "' must match whole words");
            assertFalse(rule.reply().isBlank());
        }
    }

    @Test
    @DisplayName("a command-shaped rule matches when typed with its prefix")
    void commandShapedRuleMatchesWithPrefix() {
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured());
        TriggerRule match = set.firstMatch("Steve", "!tps");
        assertNotNull(match, "!tps should match the shipped rule");
        assertEquals("!tps", match.pattern());
    }

    // ---------------------------------------------------------------- !triggers

    @Test
    @DisplayName("!triggers lists the armed rules as an embed, and hides the local-only ones")
    void triggersCommandListsArmedRules() {
        AssistantBrain brain = new AssistantBrain(new AssistantMemory());
        brain.setTriggerSet(TriggerSet.of(TriggerRule.preconfigured()));

        AssistantBrain.Decision decision = brain.command(
            SenderIdentity.unverified("Steve"), "!triggers", AssistantConfig.DEFAULTS, NOW,
            null, null, PermissionSet.permissive(), null);
        assertNotNull(decision, "!triggers must be a known command");
        assertTrue(decision.hasEmbed(), "the list is an embed, like !help");
        ReplyFormat.Embed embed = decision.embed();
        assertNotNull(embed);
        assertFalse(embed.fields().isEmpty(), "expected some rows");
        assertTrue(embed.footer().contains("trigger"), "the footer counts the triggers");
    }

    @Test
    @DisplayName("!triggers hides a rule pinned to a different player")
    void triggersHidesForeignRules() {
        AssistantBrain brain = new AssistantBrain(new AssistantMemory());
        TriggerRule pinned = new TriggerRule("secret", false, true, "Alex", "for Alex only",
            TriggerRule.ReplyMode.CHAT, 0, 0, false, false, true, "", "", "msg");
        TriggerRule open = TriggerRule.decode("hello;reply=Hi");
        assertNotNull(open);
        brain.setTriggerSet(TriggerSet.of(List.of(pinned, open)));

        AssistantBrain.Decision decision = brain.command(
            SenderIdentity.unverified("Steve"), "!triggers", AssistantConfig.DEFAULTS, NOW,
            null, null, PermissionSet.permissive(), null);
        assertNotNull(decision);
        String rendered = decision.hasEmbed() ? ReplyFormat.oneLine(decision.embed(), 400)
            : decision.text();
        assertFalse(rendered.contains("secret"),
            "another player's rule must not be advertised to a stranger");
        assertTrue(rendered.contains("hello"));
    }

    // ---------------------------------------------------------------- duplicate keys

    @Test
    @DisplayName("a duplicated key is reported rather than silently resolved")
    void duplicateKeyIsReported() {
        List<String> problems = new ArrayList<>();
        TriggerRule rule = TriggerRule.decode(
            "!tps;case=false;whole=true;sender=;reply=x;mode=chat;cooldown=1000;cooldown=3000",
            problems);
        assertNotNull(rule);
        assertEquals(3000, rule.cooldownMs(), "the last value wins so the string still works");
        assertEquals(1, problems.size(), "the duplicate must be reported once");
        assertTrue(problems.get(0).contains("cooldown"));
    }

    @Test
    @DisplayName("a rule with no duplicates reports nothing")
    void cleanRuleReportsNothing() {
        List<String> problems = new ArrayList<>();
        TriggerRule rule = TriggerRule.decode("hello;reply=Hi;cooldown=1000", problems);
        assertNotNull(rule);
        assertTrue(problems.isEmpty());
    }
}
