package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a shipped trigger has to stick, and that is not free.
 *
 * <p>The saved rule list is merged with the shipped table on every load, so that an install which
 * saved its rules before a command shipped still gains that command. The catch is that a deleted
 * rule is simply absent, which is indistinguishable from a rule that was never shipped to that
 * config. Reading absence as deletion would mean no install ever gained a rule again; reading it as
 * "not shipped yet" means every deletion is silently undone, which is what happened: "Remove" and
 * "Remove all" in the editor emptied the visible list and the merge put everything straight back.
 *
 * <p>A deletion is therefore recorded by name. These tests pin the four things that must hold at
 * once, because any two are easy and all four together are the actual problem:
 *
 * <ul>
 *   <li>a fresh install gets the whole shipped table;</li>
 *   <li>a deleted rule does not come back;</li>
 *   <li>a rule shipped after the config was written still arrives;</li>
 *   <li>restoring the defaults brings a deleted rule back.</li>
 * </ul>
 */
class TriggerRemovalTest {

    /** Every shipped rule recorded as deleted, as {@code removeAllRules} does. */
    private static Set<String> allShipped() {
        Set<String> out = new LinkedHashSet<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) out.add(rule.pattern());
        return out;
    }

    /** The stored list with one pattern deleted, as {@code removeRuleNamed} leaves it. */
    private static List<String> savedWithout(String pattern) {
        List<String> out = new ArrayList<>();
        for (TriggerRule rule : TriggerRule.preconfigured()) {
            if (!rule.pattern().equalsIgnoreCase(pattern)) out.add(rule.encode());
        }
        return out;
    }

    private static boolean answers(TriggerSet set, String line) {
        return set.firstMatch("Anyone", line) != null;
    }

    private static long count(TriggerSet set, String pattern) {
        return set.rules().stream().filter(r -> r.pattern().equalsIgnoreCase(pattern)).count();
    }

    @Test
    @DisplayName("a fresh install gets every shipped rule")
    void freshInstallGetsEverything() {
        TriggerSet set = TriggerSet.of(TriggerRule.loadSaved(List.of(), Set.of()));

        assertEquals(TriggerRule.preconfigured().size(), set.rules().size());
        assertTrue(answers(set, "cya"), "the shipped goodbye rule did not answer on a fresh install");
    }

    @Test
    @DisplayName("a deleted rule stays deleted instead of being merged back in")
    void aDeletionSticks() {
        Set<String> removed = Set.of("goodbye");

        TriggerSet set = TriggerSet.of(TriggerRule.loadSaved(savedWithout("goodbye"), removed));

        assertEquals(0, count(set, "goodbye"), "the deleted rule came back through the merge");
        assertFalse(answers(set, "cya"), "cya still answered after its rule was deleted");
        assertFalse(answers(set, "bye"), "bye still answered after its rule was deleted");
        assertEquals(TriggerRule.preconfigured().size() - 1, set.rules().size(),
            "deleting one rule should leave exactly one fewer");
    }

    @Test
    @DisplayName("a rule shipped after the config was written still arrives")
    void aNewRuleStillArrives() {
        // The saved list predates the shipping of "!cmds", and nothing was deleted. The merge must
        // add it: this is the behaviour the deletion set must not break.
        List<String> stale = savedWithout("!cmds");

        TriggerSet set = TriggerSet.of(TriggerRule.loadSaved(stale, Set.of()));

        assertEquals(1, count(set, "!cmds"), "a newly shipped rule was not added to an old config");
        assertEquals(TriggerRule.preconfigured().size(), set.rules().size());
    }

    @Test
    @DisplayName("removing every rule really empties the table")
    void removeAllEmptiesTheTable() {
        TriggerSet set = TriggerSet.of(TriggerRule.loadSaved(List.of(), allShipped()));

        assertEquals(0, set.rules().size(), "the merge refilled a table the user cleared");
        assertFalse(answers(set, "cya"));
        assertFalse(answers(set, "gg"));
        assertFalse(answers(set, "lol"));
    }

    @Test
    @DisplayName("dropping the deletion record lets a rule return, which is what Restore defaults does")
    void forgettingADeletionRestoresTheRule() {
        List<String> saved = savedWithout("goodbye");

        TriggerSet kept = TriggerSet.of(TriggerRule.loadSaved(saved, Set.of("goodbye")));
        assertEquals(0, count(kept, "goodbye"), "the rule returned before the record was dropped");

        TriggerSet restored = TriggerSet.of(TriggerRule.loadSaved(saved, Set.of()));
        assertEquals(1, count(restored, "goodbye"), "the rule did not return after the record was dropped");
        assertTrue(answers(restored, "cya"));
    }

    @Test
    @DisplayName("a deletion is matched case-insensitively and ignores surrounding space")
    void deletionMatchingIsForgiving() {
        TriggerSet set = TriggerSet.of(
            TriggerRule.loadSaved(savedWithout("goodbye"), Set.of("  GOODBYE ")));

        assertEquals(0, count(set, "goodbye"),
            "a hand-edited deletion record with different case did not match");
    }

    @Test
    @DisplayName("disabling still works alongside a deletion set")
    void disablingIsUnaffected() {
        TriggerRule rule = TriggerRule.preconfigured().stream()
            .filter(r -> r.pattern().equals("goodbye")).findFirst().orElseThrow();
        String disabled = new TriggerRule(rule.pattern(), rule.caseSensitive(), rule.wholeWord(),
            rule.sender(), rule.reply(), rule.mode(), rule.cooldownMs(), rule.sessionLimit(),
            rule.sound(), rule.remember(), false, rule.aliases(), rule.pool(), rule.whisper(),
            rule.embedMode()).encode();

        // Assert on what loads, not on the live set: TriggerSet.of drops a disabled rule by design,
        // so the live set would say 0 whether or not the merge duplicated it.
        List<TriggerRule> loaded = TriggerRule.loadSaved(List.of(disabled), Set.of());
        assertEquals(1, loaded.stream().filter(r -> r.pattern().equals("goodbye")).count(),
            "the disabled rule was duplicated by the merge");
        assertFalse(loaded.stream().filter(r -> r.pattern().equals("goodbye"))
            .findFirst().orElseThrow().enabled(), "the disabled rule came back enabled");

        assertFalse(answers(TriggerSet.of(loaded), "cya"), "a disabled rule answered");
    }

    @Test
    @DisplayName("a saved rule wins over a deletion record for the same pattern")
    void savedRuleBeatsDeletionRecord() {
        // The module clears the record when a rule is added or re-enabled, but a hand-edited config
        // can still hold both. The saved copy is the user's explicit choice, so it must answer.
        TriggerRule rule = TriggerRule.preconfigured().stream()
            .filter(r -> r.pattern().equals("goodbye")).findFirst().orElseThrow();
        List<TriggerRule> loaded = TriggerRule.loadSaved(List.of(rule.encode()), Set.of("goodbye"));

        assertEquals(1, loaded.stream().filter(r -> r.pattern().equals("goodbye")).count(),
            "the merge added a second copy of a rule that was also saved");
        assertTrue(answers(TriggerSet.of(loaded), "cya"),
            "a saved rule was silenced by a stale deletion record");
    }

    @Test
    @DisplayName("a deletion record is matched on the pattern, not on an alias of it")
    void deletionRecordHoldsThePattern() {
        // "cya" is an alias of "goodbye". A record holding the word the player typed would not match
        // the pattern the merge compares against, so nothing would be removed.
        TriggerRule goodbye = TriggerRule.preconfigured().stream()
            .filter(r -> r.pattern().equals("goodbye")).findFirst().orElseThrow();
        assertTrue(goodbye.aliasList().stream().anyMatch(a -> a.strip().equalsIgnoreCase("cya")),
            "the shipped goodbye rule no longer answers \"cya\", so this test no longer tests it");

        Set<String> byAlias = Set.of("cya");
        List<TriggerRule> loaded = TriggerRule.loadSaved(List.of(), byAlias);
        assertTrue(loaded.stream().anyMatch(r -> r.pattern().equals("goodbye")),
            "a record holding an alias removed the rule; records are patterns, not aliases, "
                + "and the writer resolves the alias before recording");
    }
}
