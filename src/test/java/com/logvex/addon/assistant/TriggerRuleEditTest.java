package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.TriggerRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-field editors behind the rule screen.
 *
 * <p>Fifteen record fields make a positional rebuild easy to get wrong by one slot, and a rule with
 * its cooldown in the session-limit slot still loads — it just behaves wrongly in a way nobody
 * notices until chat is flooded. So each editor is checked to change exactly one field and leave
 * the rest alone, and to survive an encode/decode round trip.
 */
class TriggerRuleEditTest {

    private static TriggerRule sample() {
        return new TriggerRule("!tps", true, true, "Alice", "TPS {tps}", TriggerRule.ReplyMode.PRIVATE,
            2500L, 5, true, true, true, "!ping,!lag", "hello|hi", "/msg", TriggerRule.EmbedMode.ON);
    }

    @Test
    @DisplayName("each editor changes its own field and nothing else")
    void editorsAreFieldLocal() {
        TriggerRule base = sample();

        assertEquals("!mspt", base.withPattern("!mspt").pattern());
        assertEquals("other", base.withReply("other").reply());
        assertEquals("Bob", base.withSender("Bob").sender());
        assertEquals(TriggerRule.ReplyMode.CHAT, base.withMode(TriggerRule.ReplyMode.CHAT).mode());
        assertEquals(900L, base.withCooldownMs(900).cooldownMs());
        assertEquals(2, base.withSessionLimit(2).sessionLimit());
        assertFalse(base.withCaseSensitive(false).caseSensitive());
        assertFalse(base.withWholeWord(false).wholeWord());
        assertFalse(base.withSound(false).sound());
        assertFalse(base.withRemember(false).remember());
        assertFalse(base.withEnabled(false).enabled());
        assertEquals(TriggerRule.EmbedMode.OFF, base.withEmbedMode(TriggerRule.EmbedMode.OFF).embedMode());
        assertEquals("!x", base.withAliases("!x").aliases());
        assertEquals("a|b", base.withPool("a|b").pool());
        assertEquals("/w", base.withWhisper("/w").whisper());
    }

    @Test
    @DisplayName("an edit leaves every other field exactly as it was")
    void editKeepsTheRest() {
        TriggerRule base = sample();
        TriggerRule edited = base.withReply("changed");

        assertEquals(base.pattern(), edited.pattern());
        assertEquals(base.sender(), edited.sender());
        assertEquals(base.mode(), edited.mode());
        assertEquals(base.cooldownMs(), edited.cooldownMs());
        assertEquals(base.sessionLimit(), edited.sessionLimit());
        assertEquals(base.sound(), edited.sound());
        assertEquals(base.remember(), edited.remember());
        assertEquals(base.enabled(), edited.enabled());
        assertEquals(base.aliases(), edited.aliases());
        assertEquals(base.pool(), edited.pool());
        assertEquals(base.whisper(), edited.whisper());
        assertEquals(base.embedMode(), edited.embedMode());
        assertNotSame(base, edited, "a record edit returns a new value rather than mutating");
    }

    @Test
    @DisplayName("an edited rule survives a round trip through its stored form")
    void editRoundTrips() {
        TriggerRule edited = sample().withPattern("!mspt").withCooldownMs(750).withEnabled(false);

        TriggerRule decoded = TriggerRule.decode(edited.encode());

        assertEquals(edited, decoded);
        assertFalse(decoded.enabled(), "a disabled rule must stay disabled after a save");
    }

    @Test
    @DisplayName("a negative cooldown or limit is clamped, so a typo cannot disable pacing")
    void negativeValuesClampToZero() {
        assertEquals(0L, sample().withCooldownMs(-5000).cooldownMs());
        assertEquals(0, sample().withSessionLimit(-3).sessionLimit());
    }

    @Test
    @DisplayName("a null embed mode falls back to auto rather than failing")
    void nullEmbedModeFallsBack() {
        assertEquals(TriggerRule.EmbedMode.AUTO, sample().withEmbedMode(null).embedMode());
    }

    @Test
    @DisplayName("enabling and disabling is reversible")
    void enabledRoundTrips() {
        TriggerRule off = sample().withEnabled(false);
        assertTrue(off.withEnabled(true).enabled(), "a rule switched off can be switched back on");
    }
}
