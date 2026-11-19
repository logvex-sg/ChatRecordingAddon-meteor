package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.CommandPermission;
import com.logvex.addon.assistant.core.PermissionLevel;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration written by an older build must still load, and a rule written by this build must
 * survive a save and reload unchanged.
 *
 * <p>A settings format that silently loses a permission rule on upgrade is a security problem, not
 * a cosmetic one: the command would fall back to its default level without anyone being told.
 */
class ConfigMigrationTest {

    // ------------------------------------------------------------------ trigger rules

    @Test
    @DisplayName("a rule written before aliases and pools existed still loads")
    void legacyTriggerRuleLoads() {
        // Exactly the fields the previous build wrote, with no aliases or pool keys.
        TriggerRule rule = TriggerRule.decode("hello;case=false;whole=false;sender=;reply=Hi;"
            + "mode=chat;cooldown=3000;limit=0;sound=false;remember=false;enabled=true");

        assertNotNull(rule);
        assertEquals("hello", rule.pattern());
        assertEquals("Hi", rule.reply());
        assertTrue(rule.aliasList().isEmpty());
        assertEquals("", rule.pool());
        assertTrue(TriggerSet.matches(rule, "hello"));
    }

    @Test
    @DisplayName("a legacy rule keeps matching only its pattern, not a stray alias")
    void legacyRuleHasNoPhantomAliases() {
        TriggerRule rule = TriggerRule.decode("hello;reply=Hi");

        assertNotNull(rule);
        assertFalse(TriggerSet.matches(rule, "hi there"),
            "a legacy rule must not start matching its own reply");
    }

    @Test
    @DisplayName("a rule survives encode and decode with its aliases and pool")
    void triggerRuleRoundTrips() {
        TriggerRule original = new TriggerRule("ping", false, false, "", "pong|pong!",
            TriggerRule.ReplyMode.NOTIFY, 1_500L, 5, true, true, true, "latency,ms", "bye");

        TriggerRule decoded = TriggerRule.decode(original.encode());

        assertNotNull(decoded);
        assertEquals(original.pattern(), decoded.pattern());
        assertEquals(original.reply(), decoded.reply());
        assertEquals(original.mode(), decoded.mode());
        assertEquals(original.cooldownMs(), decoded.cooldownMs());
        assertEquals(original.sessionLimit(), decoded.sessionLimit());
        assertEquals(original.sound(), decoded.sound());
        assertEquals(original.remember(), decoded.remember());
        assertEquals(original.enabled(), decoded.enabled());
        assertEquals(original.aliasList(), decoded.aliasList());
        assertEquals(original.pool(), decoded.pool());
    }

    @Test
    @DisplayName("an empty or blank rule decodes to null rather than a rule that matches everything")
    void blankRuleDecodesToNull() {
        assertNull(TriggerRule.decode(null));
        assertNull(TriggerRule.decode(""));
        assertNull(TriggerRule.decode("   "));
        assertNull(TriggerRule.decode(";reply=Hi"));
    }

    @Test
    @DisplayName("an unknown key from a newer build is ignored, not fatal")
    void unknownKeyIsIgnored() {
        TriggerRule rule = TriggerRule.decode("hello;reply=Hi;futureoption=42");

        assertNotNull(rule);
        assertEquals("Hi", rule.reply());
    }

    // ------------------------------------------------------------------ permission rules

    @Test
    @DisplayName("a permission rule survives encode and decode")
    void commandPermissionRoundTrips() {
        CommandPermission original = new CommandPermission("remember",
            PermissionLevel.WHITELIST, List.of("Alice", "Bob"), true);

        CommandPermission decoded = CommandPermission.decode(original.encode());

        assertNotNull(decoded);
        assertEquals(original.command(), decoded.command());
        assertEquals(original.level(), decoded.level());
        assertEquals(original.users(), decoded.users());
        assertEquals(original.enabled(), decoded.enabled());
    }

    @Test
    @DisplayName("a legacy rule with only whitelist=true becomes the whitelist level")
    void legacyWhitelistFlagMigrates() {
        CommandPermission decoded = CommandPermission.decode("stats;whitelist=true;allowed=Alice,Bob");

        assertNotNull(decoded);
        assertEquals(PermissionLevel.WHITELIST, decoded.level());
        assertEquals(List.of("Alice", "Bob"), decoded.users());
    }

    @Test
    @DisplayName("a legacy rule with whitelist=false keeps the default level")
    void legacyOpenFlagMigrates() {
        CommandPermission decoded = CommandPermission.decode("stats;whitelist=false");

        assertNotNull(decoded);
        assertEquals(CommandPermission.DEFAULT_LEVEL, decoded.level());
    }

    @Test
    @DisplayName("a rule with no command name is dropped, never applied to everything")
    void namelessRuleIsDropped() {
        assertNull(CommandPermission.decode(null));
        assertNull(CommandPermission.decode(""));
        assertNull(CommandPermission.decode(";;;"));
        assertNull(CommandPermission.decode(";level=everyone"));
    }

    @Test
    @DisplayName("a disabled rule does not grant access")
    void disabledRuleDoesNotGrantAccess() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("remember;level=everyone;enabled=false"), PermissionSet.DefaultPolicy.CLOSED, true);

        // The rule exists but is switched off, so the command is switched off — not merely narrowed.
        assertEquals(PermissionLevel.DISABLED, set.effectiveLevel("remember"));
    }

    @Test
    @DisplayName("a disabled command is refused even for the local player")
    void disabledCommandIsRefusedForLocalPlayer() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("remember;level=everyone;enabled=false"), PermissionSet.DefaultPolicy.OPEN, true);

        // "Disabled" has to mean disabled. If it resolved to local-only the local player could
        // still run a command they had explicitly switched off.
        assertFalse(set.permits("remember",
            com.logvex.addon.assistant.core.SenderIdentity.local("Me", null)).allowed());
        assertFalse(set.permits("remember",
            com.logvex.addon.assistant.core.SenderIdentity.unverified("Stranger")).allowed());
    }

    @Test
    @DisplayName("an empty configuration under the closed policy closes everything")
    void emptyConfigIsClosedUnderClosedPolicy() {
        PermissionSet set = PermissionSet.of(true, List.of(), List.of(),
            PermissionSet.DefaultPolicy.CLOSED, true);

        assertTrue(set.permits("remember", com.logvex.addon.assistant.core.SenderIdentity
            .unverified("Stranger")).allowed() == false);
        assertTrue(set.restricted("remember"));
    }
}
