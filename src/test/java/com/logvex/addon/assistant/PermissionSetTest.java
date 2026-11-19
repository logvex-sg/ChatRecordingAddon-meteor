package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.PermissionLevel;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.assistant.core.SenderIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission model: each level, the identity rules, and the ways a caller might try to slip past
 * a rule.
 *
 * <p>The bypass tests are the point of this file. A permission check that only works for the obvious
 * spelling is not a permission check, so each shortcut is pinned by its own test.
 */
class PermissionSetTest {
    private static final String NOTCH_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final UUID NOTCH = UUID.fromString(NOTCH_UUID);

    private static SenderIdentity player(String name) {
        return SenderIdentity.unverified(name);
    }

    private static SenderIdentity verified(String name, String uuid) {
        return new SenderIdentity(name, UUID.fromString(uuid), false);
    }

    // ------------------------------------------------------------------ levels

    @Test
    @DisplayName("with permissions off, every command is allowed")
    void permissionsOffAllowsEverything() {
        PermissionSet set = PermissionSet.permissive();
        assertFalse(set.enabled());
        assertTrue(set.permits("remember", player("Anyone")).allowed());
    }

    @Test
    @DisplayName("the everyone level allows any sender")
    void everyoneAllowsAnyone() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("help;level=everyone"), PermissionSet.DefaultPolicy.OPEN, true);

        assertTrue(set.permits("help", player("Random")).allowed());
    }

    @Test
    @DisplayName("the whitelist level allows only the listed players")
    void whitelistAllowsOnlyListed() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne,PlayerTwo"),
            PermissionSet.DefaultPolicy.OPEN, true);

        assertTrue(set.permits("stats", player("PlayerOne")).allowed());
        assertTrue(set.permits("stats", player("PlayerTwo")).allowed());
        assertFalse(set.permits("stats", player("PlayerThree")).allowed());
    }

    @Test
    @DisplayName("the whitelist level with nobody listed allows nobody")
    void emptyWhitelistAllowsNobody() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users="), PermissionSet.DefaultPolicy.OPEN, true);

        assertFalse(set.permits("stats", player("Anyone")).allowed());
    }

    @Test
    @DisplayName("the administrators level allows only configured administrators")
    void administratorsAllowsOnlyAdmins() {
        PermissionSet set = PermissionSet.of(true, List.of("Owner"),
            List.of("memory;level=administrators"), PermissionSet.DefaultPolicy.OPEN, true);

        assertTrue(set.permits("memory", player("Owner")).allowed());
        assertFalse(set.permits("memory", player("Guest")).allowed());
    }

    @Test
    @DisplayName("the administrators level with no administrators configured allows nobody")
    void noAdministratorsAllowsNobody() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("memory;level=administrators"), PermissionSet.DefaultPolicy.OPEN, true);

        assertFalse(set.permits("memory", player("Owner")).allowed(),
            "an empty administrator list must not mean everyone");
    }

    @Test
    @DisplayName("the local-only level allows the local player and refuses chat")
    void localOnlyAllowsOnlySelf() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);

        assertTrue(set.permits("remember", SenderIdentity.local("Me", null)).allowed());
        assertFalse(set.permits("remember", player("Other")).allowed());
    }

    // ------------------------------------------------------------------ identity

    @Test
    @DisplayName("a uuid-pinned entry matches the account, so a renamed player still matches")
    void uuidPinnedEntryMatchesAccount() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne=" + NOTCH_UUID),
            PermissionSet.DefaultPolicy.OPEN, true);

        assertTrue(set.permits("stats", verified("PlayerOne", NOTCH_UUID)).allowed());
        // The same account under a new name is still the same account, so it still matches: the pin
        // is on the UUID, and a name change must not lock a player out of their own command.
        assertTrue(set.permits("stats", verified("RenamedPlayer", NOTCH_UUID)).allowed());
        // A different account that happens to use the listed name must not match.
        assertFalse(set.permits("stats", verified("PlayerOne",
            "00000000-0000-0000-0000-000000000002")).allowed());
    }

    @Test
    @DisplayName("a spoofed name cannot use a uuid-pinned entry")
    void spoofedNameCannotUsePinnedEntry() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne=" + NOTCH_UUID),
            PermissionSet.DefaultPolicy.OPEN, true);

        // The attacker types the whitelisted name but their account is a different one.
        SenderIdentity impostor = verified("PlayerOne",
            "00000000-0000-0000-0000-000000000001");
        assertFalse(set.permits("stats", impostor).allowed());
    }

    @Test
    @DisplayName("an unverified sender cannot satisfy a uuid-pinned entry")
    void unverifiedCannotSatisfyPinnedEntry() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne=" + NOTCH_UUID),
            PermissionSet.DefaultPolicy.OPEN, true);

        assertFalse(set.permits("stats", player("PlayerOne")).allowed(),
            "a name alone is not proof of the account");
    }

    @Test
    @DisplayName("strict name mode refuses a name-only entry for an unverified sender")
    void strictNameModeNeedsVerification() {
        PermissionSet strict = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne"),
            PermissionSet.DefaultPolicy.OPEN, false);

        assertFalse(strict.permits("stats", player("PlayerOne")).allowed());
        assertTrue(strict.permits("stats", verified("PlayerOne", NOTCH_UUID)).allowed());
    }

    @Test
    @DisplayName("name matching ignores case")
    void nameMatchingIgnoresCase() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne"),
            PermissionSet.DefaultPolicy.OPEN, true);

        assertTrue(set.permits("stats", player("playerone")).allowed());
        assertTrue(set.permits("stats", player("PLAYERONE")).allowed());
    }

    // ------------------------------------------------------------------ unlisted commands

    @Test
    @DisplayName("an unlisted command is open under the open policy")
    void unlistedIsOpenUnderOpenPolicy() {
        PermissionSet set = PermissionSet.of(true, List.of(), List.of(),
            PermissionSet.DefaultPolicy.OPEN, true);

        assertEquals(PermissionLevel.EVERYONE, set.effectiveLevel("anything"));
        assertTrue(set.permits("anything", player("Anyone")).allowed());
    }

    @Test
    @DisplayName("an unlisted command is local-only under the closed policy")
    void unlistedIsClosedUnderClosedPolicy() {
        PermissionSet set = PermissionSet.of(true, List.of(), List.of(),
            PermissionSet.DefaultPolicy.CLOSED, true);

        assertEquals(PermissionLevel.LOCAL_ONLY, set.effectiveLevel("anything"));
        assertFalse(set.permits("anything", player("Anyone")).allowed());
        assertTrue(set.permits("anything", SenderIdentity.local("Me", null)).allowed());
    }

    // ------------------------------------------------------------------ malformed configuration

    @Test
    @DisplayName("an unreadable rule falls back to the conservative default, never to open")
    void unreadableRuleIsConservative() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of(";;;", "stats;level=not-a-level;users=PlayerOne"),
            PermissionSet.DefaultPolicy.CLOSED, true);

        // "stats" has a rule whose level was unreadable, so the level stays the conservative default.
        assertFalse(set.permits("stats", player("PlayerOne")).allowed(),
            "an unrecognised level must not silently open the command");
    }

    @Test
    @DisplayName("a null sender is refused rather than treated as the local player")
    void nullSenderIsRefused() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);

        assertFalse(set.permits("stats", null).allowed());
    }

    @Test
    @DisplayName("a denial carries a reason a player can act on")
    void denialExplainsItself() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne"),
            PermissionSet.DefaultPolicy.OPEN, true);

        PermissionSet.Verdict verdict = set.permits("stats", player("Guest"));
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("stats"));
    }

    @Test
    @DisplayName("restricted reports the commands a user has actually locked down")
    void restrictedReportsLockedCommands() {
        PermissionSet set = PermissionSet.of(true, List.of(),
            List.of("stats;level=whitelist;users=PlayerOne", "help;level=everyone"),
            PermissionSet.DefaultPolicy.OPEN, true);

        assertTrue(set.restricted("stats"));
        assertFalse(set.restricted("help"));
        assertFalse(set.restricted("unlisted"));
    }
}
