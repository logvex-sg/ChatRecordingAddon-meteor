package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerAudience;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who the assistant answers when it was not asked a direct question.
 *
 * <p>This pins the fix for a real complaint: the shipped table contains courtesies ("gg", "lol",
 * "ty", "wb"), so a client with default settings whispered a reply to nearly every line of ordinary
 * chatter on a public server. Measured on the shipped table, 11 of 14 common lines produced a reply
 * from a stranger. That is the assistant talking to people the user never invited, so the default is
 * now "only me" and answering strangers has to be turned on.
 */
class TriggerAudienceTest {

    private static final long NOW = 1_700_000_000_000L;

    /** Lines that are ordinary chatter, not questions aimed at a bot. */
    private static final String[] CHATTER = {
        "hello", "gg", "lol", "thanks", "ty", "wb", "morning", "bye", "nice one", "gl", "np", "sorry"
    };

    private static AssistantRuntime runtime(AssistantConfig config) {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.of(TriggerRule.preconfigured()));
        runtime.setConfig(config);
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    private static AssistantConfig config(TriggerAudience audience, Set<String> allowed) {
        return new AssistantConfig("Assistant", true, true, "!", TriggerRule.ReplyMode.NOTIFY, true,
            1_000, 200, audience, allowed);
    }

    private static int repliesTo(AssistantRuntime runtime, SenderIdentity sender, String... lines) {
        int replied = 0;
        for (String line : lines) {
            var decision = runtime.handle(sender, line, NOW, ServerStats.OFFLINE,
                StatusCommands.Settings.DEFAULTS);
            if (decision != null && decision.acts()) replied++;
        }
        return replied;
    }

    @Test
    @DisplayName("a stranger gets no reply to ordinary chatter under the default settings")
    void strangersAreIgnoredByDefault() {
        // The regression: this used to be 11 of 12. The default config must be quiet around people
        // the user has not named, because "gg" is not a question and a whisper is still a message.
        AssistantRuntime runtime = runtime(AssistantConfig.DEFAULTS);
        int replied = repliesTo(runtime, SenderIdentity.unverified("RandomStranger"), CHATTER);
        assertEquals(0, replied,
            "the default configuration answered " + replied + " of " + CHATTER.length
                + " plain chat lines from a stranger");
    }

    @Test
    @DisplayName("the local player is always answered, whatever the audience is")
    void localPlayerIsAlwaysAnswered() {
        for (TriggerAudience audience : TriggerAudience.values()) {
            AssistantRuntime runtime = runtime(config(audience, Set.of()));
            SenderIdentity me = SenderIdentity.local("Owner", null);
            assertTrue(repliesTo(runtime, me, "hello") > 0,
                "the local player was ignored under " + audience);
        }
    }

    @Test
    @DisplayName("a listed name is answered under the allowlist")
    void allowlistedPlayerIsAnswered() {
        AssistantRuntime runtime = runtime(config(TriggerAudience.ALLOWLIST, Set.of("friend")));
        // Case-insensitive: a chat line's capitalisation carries no meaning.
        assertTrue(repliesTo(runtime, SenderIdentity.unverified("Friend"), "hello") > 0);
    }

    @Test
    @DisplayName("an unlisted name is still ignored under the allowlist")
    void unlistedPlayerIsIgnored() {
        AssistantRuntime runtime = runtime(config(TriggerAudience.ALLOWLIST, Set.of("friend")));
        assertEquals(0, repliesTo(runtime, SenderIdentity.unverified("RandomStranger"), "hello"));
    }

    @Test
    @DisplayName("an empty allowlist means only the local player")
    void emptyAllowlistMeansOnlyMe() {
        TriggerAudience audience = TriggerAudience.ALLOWLIST;
        assertTrue(audience.allows(SenderIdentity.local("Owner", null), Set.of()));
        assertFalse(audience.allows(SenderIdentity.unverified("Anyone"), Set.of()));
    }

    @Test
    @DisplayName("Everyone restores the old behaviour, for users who want it")
    void everyoneIsOptIn() {
        AssistantRuntime runtime = runtime(config(TriggerAudience.EVERYONE, Set.of()));
        assertTrue(repliesTo(runtime, SenderIdentity.unverified("RandomStranger"), "hello") > 0,
            "Everyone should answer a stranger; opting in has to actually work");
    }

    @Test
    @DisplayName("LocalOnly ignores the allowlist")
    void localOnlyIgnoresTheList() {
        TriggerAudience audience = TriggerAudience.LOCAL_ONLY;
        assertFalse(audience.allows(SenderIdentity.unverified("Friend"), Set.of("friend")));
        assertTrue(audience.allows(SenderIdentity.local("Owner", null), Set.of("friend")));
    }

    @Test
    @DisplayName("direct commands still work for a stranger, so the gate is trigger-only")
    void commandsAreNotGatedByTheTriggerAudience() {
        // The audience governs unprompted replies. A player who types "!help" has asked for
        // something, and that path follows Permissions, not this gate. Gating both would make the
        // assistant unreachable for everyone but the operator, which is not what was reported.
        AssistantRuntime runtime = runtime(AssistantConfig.DEFAULTS);
        var decision = runtime.handle(SenderIdentity.unverified("RandomStranger"), "!help", NOW,
            ServerStats.OFFLINE, StatusCommands.Settings.DEFAULTS);
        assertNotNull(decision);
        assertTrue(decision.acts(), "!help from a stranger should still be answered");
    }

    @Test
    @DisplayName("a line from someone out of earshot is ignored, not refused")
    void gatedLineIsIgnoredNotRefused() {
        // denied() is documented as "commands the permission gate refused". A stranger's "hello" was
        // never a request, so counting it as a denial would make the session summary claim the
        // assistant rejected people it simply did not hear.
        AssistantRuntime runtime = runtime(AssistantConfig.DEFAULTS);
        runtime.handle(SenderIdentity.unverified("RandomStranger"), "hello", NOW, ServerStats.OFFLINE,
            StatusCommands.Settings.DEFAULTS);
        assertEquals(0, runtime.denied());
        assertEquals(1, runtime.ignored());
    }

    @Test
    @DisplayName("the shipped table still matches the chatter it was written for")
    void theTableStillMatches() {
        // The gate must not be "fixed" by quietly deleting the courtesies from the table: a user who
        // opts in to Everyone expects them to work. This asserts the table still matches, so a
        // future edit cannot make the earlier tests pass by removing the rules instead of gating.
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured());
        int matched = 0;
        for (String line : CHATTER) {
            if (set.firstMatch("Anyone", line) != null) matched++;
        }
        assertTrue(matched >= CHATTER.length - 3,
            "the shipped table lost its courtesies: only " + matched + " of " + CHATTER.length
                + " matched");
    }

    @Test
    @DisplayName("a config built the old way gets the safe default")
    void legacyConstructorUsesSafeDefault() {
        AssistantConfig legacy = AssistantConfig.basic("Assistant", true, true, "!",
            TriggerRule.ReplyMode.NOTIFY, true, 20, 200);
        assertEquals(TriggerAudience.ALLOWLIST, legacy.triggerAudience());
        assertTrue(legacy.triggerAllowList().isEmpty());
        assertFalse(legacy.triggerAudience().allows(SenderIdentity.unverified("Stranger"), List.of()));
    }

    @Test
    @DisplayName("the audience describes itself for the GUI and status output")
    void descriptionIsReadable() {
        assertEquals("anyone in chat", TriggerAudience.EVERYONE.describe(0));
        assertEquals("only you", TriggerAudience.LOCAL_ONLY.describe(5));
        assertEquals("only you (no names listed)", TriggerAudience.ALLOWLIST.describe(0));
        assertEquals("you and 1 name", TriggerAudience.ALLOWLIST.describe(1));
        assertEquals("you and 3 names", TriggerAudience.ALLOWLIST.describe(3));
    }
}
