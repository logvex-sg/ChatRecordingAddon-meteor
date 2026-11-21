package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ChatMacro;
import com.logvex.addon.assistant.core.RemoteMacroGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Letting another player run a macro means running a command as the operator, so this gate is the
 * security boundary. These tests pin the parts that must never loosen: off by default, unshared
 * macros unreachable, and a per-sender cooldown.
 */
class RemoteMacroGateTest {

    private static ChatMacro shared(String shortcut) {
        return new ChatMacro(shortcut, shortcut, "/spawn", "", true, List.of(), true);
    }

    private static ChatMacro local(String shortcut) {
        return new ChatMacro(shortcut, shortcut, "/spawn", "", true, List.of(), false);
    }

    @Test
    @DisplayName("the shipped gate allows nothing")
    void disabledByDefault() {
        RemoteMacroGate gate = RemoteMacroGate.disabled();
        assertEquals(RemoteMacroGate.Verdict.DISABLED, gate.check("Alex", shared("!spawn"), 0L));
    }

    @Test
    @DisplayName("an unshared macro is never reachable remotely")
    void unsharedIsRefused() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of(), 0L);
        assertEquals(RemoteMacroGate.Verdict.NOT_SHARED, gate.check("Alex", local("!spawn"), 0L));
    }

    @Test
    @DisplayName("an empty allow-list means everyone, once enabled")
    void emptyAllowListIsEveryone() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of(), 0L);
        assertTrue(gate.check("Alex", shared("!spawn"), 0L).allowed());
    }

    @Test
    @DisplayName("an allow-list is matched case-insensitively")
    void allowListMatchesCaseInsensitively() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of("alex"), 0L);
        assertTrue(gate.check("Alex", shared("!spawn"), 0L).allowed());
        assertEquals(RemoteMacroGate.Verdict.NOT_ALLOWED,
            gate.check("Steve", shared("!spawn"), 0L));
    }

    @Test
    @DisplayName("the cooldown applies after a run is recorded")
    void cooldownAfterRecord() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of(), 5_000L);
        assertTrue(gate.check("Alex", shared("!spawn"), 1_000L).allowed());

        gate.record("Alex", 1_000L);
        assertEquals(RemoteMacroGate.Verdict.COOLDOWN,
            gate.check("Alex", shared("!spawn"), 2_000L));
        assertTrue(gate.check("Alex", shared("!spawn"), 6_000L).allowed());
    }

    @Test
    @DisplayName("a refused request does not start the cooldown")
    void refusalDoesNotConsumeCooldown() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of("Alex"), 5_000L);
        // Steve is not allowed; asking must not lock Alex out.
        assertEquals(RemoteMacroGate.Verdict.NOT_ALLOWED,
            gate.check("Steve", shared("!spawn"), 1_000L));
        assertTrue(gate.check("Alex", shared("!spawn"), 1_000L).allowed());
    }

    @Test
    @DisplayName("the cooldown is per sender")
    void cooldownIsPerSender() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of(), 5_000L);
        gate.record("Alex", 1_000L);
        assertEquals(RemoteMacroGate.Verdict.COOLDOWN,
            gate.check("Alex", shared("!spawn"), 2_000L));
        assertTrue(gate.check("Steve", shared("!spawn"), 2_000L).allowed());
    }

    @Test
    @DisplayName("a malformed or missing macro is refused rather than run")
    void invalidMacroRefused() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of(), 0L);
        assertEquals(RemoteMacroGate.Verdict.INVALID, gate.check("Alex", null, 0L));
        // A template that is not a command is not a valid macro.
        ChatMacro broken = new ChatMacro("!x", "!x", "not a command", "", true, List.of(), true);
        assertEquals(RemoteMacroGate.Verdict.INVALID, gate.check("Alex", broken, 0L));
    }

    @Test
    @DisplayName("a blank sender is refused")
    void blankSenderRefused() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of(), 0L);
        assertEquals(RemoteMacroGate.Verdict.INVALID, gate.check("  ", shared("!spawn"), 0L));
    }

    @Test
    @DisplayName("reset forgets every cooldown")
    void resetClearsCooldowns() {
        RemoteMacroGate gate = new RemoteMacroGate(true, List.of(), 5_000L);
        gate.record("Alex", 1_000L);
        assertEquals(1, gate.tracked());

        gate.reset();
        assertEquals(0, gate.tracked());
        assertTrue(gate.check("Alex", shared("!spawn"), 1_100L).allowed());
    }

    @Test
    @DisplayName("the shared flag survives an encode/decode round trip")
    void remoteFlagRoundTrips() {
        ChatMacro shared = shared("!spawn");
        String encoded = shared.encode();
        assertTrue(encoded.endsWith("; remote"), encoded);

        ChatMacro decoded = ChatMacro.decode(encoded);
        assertNotNull(decoded);
        assertTrue(decoded.remote());

        ChatMacro plain = ChatMacro.decode("!spawn => /spawn");
        assertNotNull(plain);
        assertFalse(plain.remote(), "a hand-written line must not become shared by accident");
    }

    @Test
    @DisplayName("withRemote flips only the sharing flag")
    void withRemoteKeepsEverythingElse() {
        ChatMacro base = ChatMacro.decode("!home <name> => /home {name} ; desc=Go home ; aliases=!h");
        assertNotNull(base);
        ChatMacro shared = base.withRemote(true);

        assertTrue(shared.remote());
        assertEquals(base.usage(), shared.usage());
        assertEquals(base.template(), shared.template());
        assertEquals(base.description(), shared.description());
        assertEquals(base.aliases(), shared.aliases());
    }
}