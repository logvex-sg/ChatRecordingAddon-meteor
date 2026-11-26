package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerAudience;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code !trigger on|off <name>}: switching one shipped rule on or off from chat.
 *
 * <p>The command exists because the alternative is opening the module screen mid-fight to silence
 * one noisy answer. The properties worth pinning are the ones that make it safe: it is operator
 * only, it changes one rule and not the table, and a rule switched off can be switched back on -
 * which is not free, because a {@link TriggerSet} holds only the rules that are on.
 */
class TriggerToggleTest {

    /** The saved rule list, mutated exactly as the module's writer does. */
    private final List<String> saved = new ArrayList<>(
        TriggerRule.preconfigured().stream().map(TriggerRule::encode).toList());

    private AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(AssistantConfig.DEFAULTS.withAudience(TriggerAudience.EVERYONE));
        runtime.setDuplicateFilter(false);
        runtime.brain().setTriggerToggle(this::setEnabled);
        runtime.brain().setTriggerCatalog(() -> TriggerRule.loadSaved(saved));
        rebuild(runtime);
        return runtime;
    }

    /** The module's writer: flip one rule, persist, rebuild. Returns null when it was found. */
    private String setEnabled(String pattern, boolean enabled) {
        for (int i = 0; i < saved.size(); i++) {
            TriggerRule rule = TriggerRule.decode(saved.get(i));
            if (rule == null || !rule.pattern().equalsIgnoreCase(pattern)) continue;
            saved.set(i, new TriggerRule(rule.pattern(), rule.caseSensitive(), rule.wholeWord(),
                rule.sender(), rule.reply(), rule.mode(), rule.cooldownMs(), rule.sessionLimit(),
                rule.sound(), rule.remember(), enabled, rule.aliases(), rule.pool(), rule.whisper(),
                rule.embedMode()).encode());
            return null;
        }
        return "No trigger is called '" + pattern + "'.";
    }

    private void rebuild(AssistantRuntime runtime) {
        TriggerSet set = TriggerSet.of(TriggerRule.loadSaved(saved));
        runtime.setTriggers(set);
        runtime.brain().setTriggerSet(set);
    }

    private static AssistantBrain.Decision ask(AssistantRuntime runtime, String who, String message) {
        return runtime.handle(SenderIdentity.unverified(who), message, 5_000L, ServerStats.OFFLINE, null);
    }

    private static AssistantBrain.Decision local(AssistantRuntime runtime, String message) {
        return runtime.handle(SenderIdentity.local("Me", null), message, 5_000L,
            ServerStats.OFFLINE, null);
    }

    @Test
    @DisplayName("an alias names the rule it belongs to, so the word a player saw can be silenced")
    void aliasAddressesItsRule() {
        AssistantRuntime runtime = runtime();

        // "cya" is an alias of the "goodbye" rule. A player types the word they saw in chat, so a
        // pattern-only lookup would refuse the one name they know.
        AssistantBrain.Decision decision = local(runtime, "!trigger off cya");
        assertFalse(decision.text().contains("No trigger is called"),
            "an alias did not resolve to its rule: " + decision.text());
        assertTrue(decision.text().contains("goodbye"),
            "the confirmation should name the rule that actually changed: " + decision.text());
    }

    @Test
    @DisplayName("switching a trigger off silences it, and switching it on brings it back")
    void offThenOn() {
        AssistantRuntime runtime = runtime();
        assertTrue(ask(runtime, "Griefer", "cya").acts(), "cya should answer before it is switched off");

        local(runtime, "!trigger off cya");
        rebuild(runtime);
        assertFalse(ask(runtime, "Griefer", "cya").acts(), "cya still answered after being switched off");

        local(runtime, "!trigger on cya");
        rebuild(runtime);
        assertTrue(ask(runtime, "Griefer", "cya").acts(), "cya did not come back");
    }

    @Test
    @DisplayName("a switched-off rule can be switched back on, even though it is not in the live set")
    void offIsStillAddressable() {
        AssistantRuntime runtime = runtime();
        local(runtime, "!trigger off cya");
        rebuild(runtime);

        // The live set no longer holds the rule, so a lookup against it would answer "no such
        // trigger" and the rule could never be restored. The catalog is what makes this work.
        AssistantBrain.Decision decision = local(runtime, "!trigger on goodbye");
        assertTrue(decision.acts());
        assertFalse(decision.text().contains("No trigger is called"),
            "the rule was not found after being switched off: " + decision.text());
    }

    @Test
    @DisplayName("the disabled flag survives a save and reload")
    void theFlagIsPersisted() {
        runtime();
        setEnabled("goodbye", false);

        TriggerRule reloaded = TriggerRule.loadSaved(saved).stream()
            .filter(rule -> rule.pattern().equals("goodbye")).findFirst().orElseThrow();
        assertFalse(reloaded.enabled(), "a disabled rule came back enabled");
    }

    @Test
    @DisplayName("the shipped defaults are not resurrected over a disabled rule")
    void mergeDoesNotResurrectADisabledRule() {
        // The load path merges the shipped table into whatever was saved, so the merge is exactly
        // where a disabled rule would silently come back on.
        List<TriggerRule> merged = TriggerRule.loadSaved(List.of(disabled("goodbye")));

        TriggerRule goodbye = merged.stream().filter(rule -> rule.pattern().equals("goodbye"))
            .findFirst().orElseThrow();
        assertFalse(goodbye.enabled());
        assertEquals(1, merged.stream().filter(rule -> rule.pattern().equals("goodbye")).count(),
            "the merge added a second, enabled copy of a disabled rule");
    }

    private static String disabled(String pattern) {
        TriggerRule rule = TriggerRule.preconfigured().stream()
            .filter(candidate -> candidate.pattern().equals(pattern)).findFirst().orElseThrow();
        return new TriggerRule(rule.pattern(), rule.caseSensitive(), rule.wholeWord(), rule.sender(),
            rule.reply(), rule.mode(), rule.cooldownMs(), rule.sessionLimit(), rule.sound(),
            rule.remember(), false, rule.aliases(), rule.pool(), rule.whisper(),
            rule.embedMode()).encode();
    }

    @Test
    @DisplayName("only the operator can switch a trigger, and a stranger is told nothing")
    void operatorOnly() {
        AssistantRuntime runtime = runtime();

        AssistantBrain.Decision stranger = ask(runtime, "Griefer", "!trigger off cya");
        assertFalse(stranger.text().toLowerCase().contains("is now off"),
            "a stranger switched a trigger off: " + stranger.text());

        assertTrue(ask(runtime, "Griefer", "cya").acts(),
            "a stranger's attempt to switch a trigger off actually silenced it");
    }

    @Test
    @DisplayName("an unknown name is reported, and a shared prefix is refused rather than guessed")
    void unknownAndAmbiguous() {
        AssistantRuntime runtime = runtime();

        assertTrue(local(runtime, "!trigger off zzzznotathing").text()
            .contains("No trigger is called"));

        AssistantBrain.Decision ambiguous = local(runtime, "!trigger off t");
        assertTrue(ambiguous.text().contains("Which one?"),
            "a prefix matching several rules was not treated as ambiguous: " + ambiguous.text());
    }

    @Test
    @DisplayName("switching one rule off leaves every other rule answering")
    void oneRuleOnly() {
        AssistantRuntime runtime = runtime();
        local(runtime, "!trigger off cya");
        rebuild(runtime);

        assertFalse(ask(runtime, "Griefer", "cya").acts());
        assertTrue(ask(runtime, "Griefer", "gg").acts(), "an unrelated trigger was silenced too");
    }

    @Test
    @DisplayName("the operator's listing marks a switched-off rule so the name can be found again")
    void listingShowsDisabledRules() {
        AssistantRuntime runtime = runtime();
        local(runtime, "!trigger off cya");
        rebuild(runtime);

        String mine = local(runtime, "!triggers").text();
        assertTrue(mine.contains("goodbye") && mine.contains("[off]"),
            "the operator cannot see the rule they switched off: " + mine);

        String theirs = ask(runtime, "Griefer", "!triggers").text();
        assertFalse(theirs.contains("goodbye"),
            "a remote player was told about a switched-off rule: " + theirs);
    }
}
