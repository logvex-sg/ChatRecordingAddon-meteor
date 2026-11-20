package com.logvex.addon.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleAuditTest {

    @Test
    @DisplayName("a name claimed twice is reported once, with its count")
    void reportsDuplicate() {
        List<ModuleAudit.Clash> clashes =
            ModuleAudit.clashes(List.of("auto-reconnect", "killaura", "auto-reconnect"));

        assertEquals(1, clashes.size());
        assertEquals("auto-reconnect", clashes.get(0).name());
        assertEquals(2, clashes.get(0).count());
    }

    @Test
    @DisplayName("distinct names are clean")
    void cleanNames() {
        assertTrue(ModuleAudit.isClean(List.of("log-reconnect", "chat-monitor", "killaura")));
        assertTrue(ModuleAudit.clashes(List.of("a", "b", "c")).isEmpty());
    }

    @Test
    @DisplayName("three claimants are counted, not collapsed to two")
    void countsEveryClaimant() {
        List<ModuleAudit.Clash> clashes =
            ModuleAudit.clashes(List.of("dupe", "dupe", "dupe"));
        assertEquals(3, clashes.get(0).count());
    }

    @Test
    @DisplayName("every clashing name is reported, not just the first")
    void reportsAllClashes() {
        List<ModuleAudit.Clash> clashes = ModuleAudit.clashes(
            List.of("b", "a", "b", "a", "safe"));
        assertEquals(List.of("a", "b"), clashes.stream().map(ModuleAudit.Clash::name).toList());
    }

    @Test
    @DisplayName("results are ordered, so the report is stable between runs")
    void ordered() {
        List<ModuleAudit.Clash> clashes =
            ModuleAudit.clashes(Arrays.asList("z", "m", "a", "z", "m", "a"));
        assertEquals(List.of("a", "m", "z"), clashes.stream().map(ModuleAudit.Clash::name).toList());
    }

    @Test
    @DisplayName("blank and null names are ignored rather than reported")
    void ignoresBlank() {
        List<String> names = Arrays.asList("", "  ", null, "", "  ");
        assertTrue(ModuleAudit.isClean(names),
            "Meteor rejects blank names outright; reporting them would bury a real clash");
    }

    @Test
    @DisplayName("an empty or null list is clean")
    void emptyIsClean() {
        assertTrue(ModuleAudit.isClean(Collections.emptyList()));
        assertTrue(ModuleAudit.isClean(null));
    }

    @Test
    @DisplayName("the summary reads as a sentence either way")
    void summary() {
        assertEquals("No module name clashes.", ModuleAudit.summary(List.of("a", "b")));
        assertEquals("1 clash: auto-reconnect (x2)",
            ModuleAudit.summary(List.of("auto-reconnect", "auto-reconnect", "killaura")));
        String two = ModuleAudit.summary(List.of("a", "a", "b", "b"));
        assertEquals("2 clashes: a (x2), b (x2)", two);
        assertFalse(two.contains("clash:"), "plural wording must match the count");
    }
}
