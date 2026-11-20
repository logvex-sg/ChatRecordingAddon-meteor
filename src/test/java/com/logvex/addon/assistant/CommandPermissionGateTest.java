package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.PermissionLevel;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission gate as it is actually reached: through {@link AssistantRuntime} and the command
 * parser, not by calling {@link PermissionSet} directly.
 *
 * <p>These tests exist because a rule that is correct in isolation can still be bypassed by the
 * path that calls it — a second spelling, a prefix, a command that runs before the check. Each test
 * here names one such shortcut.
 */
class CommandPermissionGateTest {
    private static final String OWNER_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String STRANGER_UUID = "11111111-2222-3333-4444-555555555555";

    private static AssistantRuntime runtime(PermissionSet permissions) {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setPermissions(permissions);
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    private static SenderIdentity player(String name, String uuid) {
        return new SenderIdentity(name, UUID.fromString(uuid), false);
    }

    // ------------------------------------------------------------------ the gate runs before the side effect

    @Test
    @DisplayName("a refused remember writes no fact")
    void refusedRememberWritesNothing() {
        PermissionSet permissions = PermissionSet.of(true, List.of("Owner"),
            List.of("remember;level=administrators"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        AssistantBrain.Decision decision = runtime.handle(
            player("Stranger", STRANGER_UUID), "!remember favourite = cake", 1_000L, null, null);

        assertTrue(decision.acts(), "the refusal itself is delivered");
        assertTrue(decision.denied());
        assertTrue(runtime.memory().facts().isEmpty(),
            "a refused command must not leave a fact behind");
    }

    @Test
    @DisplayName("a refused forget leaves the fact in place")
    void refusedForgetKeepsTheFact() {
        AssistantMemory memory = new AssistantMemory();
        AssistantRuntime runtime = new AssistantRuntime(memory);
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        runtime.setPermissions(PermissionSet.of(true, List.of("Owner"),
            List.of("forget;level=administrators"), PermissionSet.DefaultPolicy.OPEN, true));

        // The owner stores a fact first, through the permitted path.
        runtime.handle(player("Owner", OWNER_UUID), "!remember favourite = cake", 1_000L, null, null);
        assertFalse(memory.facts().isEmpty(), "the owner's write must succeed");

        runtime.handle(player("Stranger", STRANGER_UUID), "!forget favourite", 2_000L, null, null);
        assertFalse(memory.facts().isEmpty(), "a refused forget must not delete anything");
    }

    @Test
    @DisplayName("a refusal is counted, so a user can see it happened")
    void refusalIsCounted() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        runtime.handle(player("Stranger", STRANGER_UUID), "!remember a = b", 1_000L, null, null);
        assertEquals(1, runtime.denied());
    }

    // ------------------------------------------------------------------ bypass attempts

    @Test
    @DisplayName("an alias reaches the same rule as the canonical name")
    void aliasCannotBypassTheRule() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        String alias = com.logvex.addon.assistant.core.CommandCatalog.aliasOf("remember");
        assertNotNull(alias, "remember is expected to have an alias to test");

        AssistantBrain.Decision decision = runtime.handle(
            player("Stranger", STRANGER_UUID), "!" + alias + " a = b", 1_000L, null, null);

        assertTrue(decision.denied(), "the alias must be refused exactly like the real name");
        assertTrue(runtime.memory().facts().isEmpty());
    }

    @Test
    @DisplayName("a case change does not get past the rule")
    void caseChangeCannotBypassTheRule() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        AssistantBrain.Decision decision = runtime.handle(
            player("Stranger", STRANGER_UUID), "!ReMeMbEr a = b", 1_000L, null, null);

        assertTrue(decision.denied());
        assertTrue(runtime.memory().facts().isEmpty());
    }

    @Test
    @DisplayName("a command hidden behind extra whitespace is still parsed and refused")
    void whitespaceCannotBypassTheRule() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        AssistantBrain.Decision decision = runtime.handle(
            player("Stranger", STRANGER_UUID), "!   remember   a = b", 1_000L, null, null);

        assertTrue(decision.denied());
        assertTrue(runtime.memory().facts().isEmpty());
    }

    @Test
    @DisplayName("a spoofed sender name cannot satisfy a uuid-pinned whitelist entry")
    void spoofedNameCannotBypassPinnedEntry() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=whitelist;users=Owner=" + OWNER_UUID),
            PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        // The chat line claims to be Owner, but the resolved account is somebody else.
        AssistantBrain.Decision decision = runtime.handle(
            player("Owner", STRANGER_UUID), "!remember a = b", 1_000L, null, null);

        assertTrue(decision.denied());
        assertTrue(runtime.memory().facts().isEmpty());
    }

    @Test
    @DisplayName("an unverified name cannot satisfy a uuid-pinned whitelist entry")
    void unverifiedNameCannotBypassPinnedEntry() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=whitelist;users=Owner=" + OWNER_UUID),
            PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.unverified("Owner"), "!remember a = b", 1_000L, null, null);

        assertTrue(decision.denied());
        assertTrue(runtime.memory().facts().isEmpty());
    }

    @Test
    @DisplayName("a command that is not in the catalogue is not executed at all")
    void unknownCommandIsNotExecuted() {
        PermissionSet permissions = PermissionSet.of(true, List.of(), List.of(),
            PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        AssistantBrain.Decision decision = runtime.handle(
            player("Stranger", STRANGER_UUID), "!notacommand whatever", 1_000L, null, null);

        assertFalse(decision.acts(), "an unknown verb is ignored, not answered");
        assertEquals(0, runtime.handled());
    }

    @Test
    @DisplayName("a prefix collision does not resolve to a restricted command")
    void prefixDoesNotResolveToAnotherCommand() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("facts;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        // "factx" is not "fact" and not "facts"; it must not inherit either rule or run.
        AssistantBrain.Decision decision = runtime.handle(
            player("Stranger", STRANGER_UUID), "!factx something", 1_000L, null, null);

        assertNull(decision.acts() ? decision.text() : null);
    }

    @Test
    @DisplayName("with permissions on, an administrator can use an administrators-level command")
    void administratorCanUseAdministratorCommand() {
        PermissionSet permissions = PermissionSet.of(true, List.of("Owner=" + OWNER_UUID),
            List.of("remember;level=administrators"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        AssistantBrain.Decision decision = runtime.handle(
            player("Owner", OWNER_UUID), "!remember favourite = cake", 1_000L, null, null);

        assertFalse(decision.denied());
        assertEquals(1, runtime.memory().facts().size());
    }

    @Test
    @DisplayName("a refused command is not counted as a used command")
    void refusedCommandIsNotCountedAsUsed() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        runtime.handle(player("Stranger", STRANGER_UUID), "!remember a = b", 1_000L, null, null);

        assertEquals(0, runtime.activity().commandCount("remember"),
            "a refused command did not run, so it must not appear as used");
        assertEquals(1, runtime.denied());
    }

    @Test
    @DisplayName("the local player can always use a local-only command")
    void localPlayerCanUseLocalOnlyCommand() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);

        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.local("Me", null), "!remember favourite = cake", 1_000L, null, null);

        assertFalse(decision.denied());
        assertEquals(1, runtime.memory().facts().size());
    }

    @Test
    @DisplayName("a refusal does not consume the per-minute reply budget")
    void refusalDoesNotConsumeBudget() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);
        // Exactly one reply a minute: the tightest budget that exposes a shared counter.
        runtime.setConfig(AssistantConfig.basic("A", true, true, "!",
            TriggerRule.ReplyMode.CHAT, true, 1, 200));

        // A stranger repeats a command they may not use. Each attempt is refused, and none of them
        // may spend the local player's reply budget — otherwise anyone could silence the assistant.
        for (int i = 0; i < 3; i++) {
            runtime.handle(player("Stranger", STRANGER_UUID), "!remember a" + i + " = b",
                1_000L + i, null, null);
        }

        AssistantBrain.Decision allowed = runtime.handle(
            SenderIdentity.local("Me", null), "!remember favourite = cake", 2_000L, null, null);

        assertFalse(allowed.denied(), "the local player's own command must still be answered");
        assertTrue(allowed.acts(),
            "the refusal budget must not starve a permitted reply; rateLimited="
                + runtime.rateLimited());
        assertEquals(1, runtime.memory().facts().size());
    }

    @Test
    @DisplayName("refusals are still capped, so a stranger cannot make the assistant answer every line")
    void refusalsAreStillCapped() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);
        AssistantRuntime runtime = runtime(permissions);
        runtime.setConfig(AssistantConfig.basic("A", true, true, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 200));

        int delivered = 0;
        for (int i = 0; i < 40; i++) {
            if (runtime.handle(player("Stranger", STRANGER_UUID), "!remember k" + i + " = v",
                1_000L + i, null, null).acts()) delivered++;
        }

        assertTrue(delivered < 40,
            "the assistant must not answer an unlimited number of refusals; delivered=" + delivered);
        assertTrue(runtime.rateLimited() > 0, "the excess must be counted as rate limited");
    }

    @Test
    @DisplayName("the level reported for a command matches the level enforced")
    void reportedLevelMatchesEnforcedLevel() {
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=whitelist;users=Owner=" + OWNER_UUID),
            PermissionSet.DefaultPolicy.OPEN, true);

        assertEquals(PermissionLevel.WHITELIST, permissions.effectiveLevel("remember"));
        assertTrue(permissions.permits("remember",
            player("Owner", OWNER_UUID)).allowed());
        assertFalse(permissions.permits("remember",
            player("Stranger", STRANGER_UUID)).allowed());
    }

    // ------------------------------------------------------------------ the summary command

    @Test
    @DisplayName("the local player can send a summary, and it is tagged as one")
    void localPlayerCanSendSummary() {
        AssistantRuntime runtime = runtime(PermissionSet.permissive());
        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.local("Owner", null), "!summary weekly roundup", 1_000L, null, null);

        assertNotNull(decision);
        assertTrue(decision.hasReport(), "the summary travels on the report channel");
        assertTrue(decision.report().startsWith("summary|"),
            "the kind must be distinct so the module sends a card, not a report: "
                + decision.report());
        assertTrue(decision.report().contains("weekly roundup"), decision.report());
    }

    @Test
    @DisplayName("the summary is declared local-only, so a closed policy protects it")
    void summaryIsDeclaredLocalOnly() {
        // The catalog level is what help shows and what a CLOSED default policy falls back to. It
        // is not the only defence: with permissions switched off the brain lets anyone through, so
        // the module also refuses a non-local reporter at the point of sending. This pins the
        // declaration; the module gate is covered where it lives, because it needs a live client.
        assertEquals(PermissionLevel.LOCAL_ONLY,
            com.logvex.addon.assistant.core.CommandCatalog.level("summary"));

        AssistantRuntime runtime = runtime(PermissionSet.of(true, List.of(), List.of(),
            PermissionSet.DefaultPolicy.CLOSED, true));
        AssistantBrain.Decision decision = runtime.handle(
            player("Stranger", STRANGER_UUID), "!summary hello", 1_000L, null, null);

        assertNotNull(decision);
        assertTrue(decision.denied(), "a closed policy must refuse a stranger");
        assertFalse(decision.hasReport(), "nothing may be queued for a refused caller");
    }

    @Test
    @DisplayName("a summary with no note is still a valid request")
    void summaryWithoutNoteIsValid() {
        AssistantRuntime runtime = runtime(PermissionSet.permissive());
        AssistantBrain.Decision decision = runtime.handle(
            SenderIdentity.local("Owner", null), "!summary", 1_000L, null, null);

        assertNotNull(decision);
        assertTrue(decision.hasReport());
        assertEquals("summary|Owner|", decision.report());
    }
}
