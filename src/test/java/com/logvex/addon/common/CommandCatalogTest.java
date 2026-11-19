package com.logvex.addon.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCatalogTest {

    @Test
    @DisplayName("every entry has a usage and a description")
    void entriesAreComplete() {
        assertFalse(CommandCatalog.entries().isEmpty());
        for (CommandCatalog.Entry entry : CommandCatalog.entries()) {
            assertFalse(entry.usage().isBlank(), "usage missing: " + entry);
            assertFalse(entry.description().isBlank(), "description missing: " + entry);
            assertNotNull(entry.group());
        }
    }

    @Test
    @DisplayName("no usage appears twice, so the copied list has no duplicates")
    void usagesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (CommandCatalog.Entry entry : CommandCatalog.entries()) {
            assertTrue(seen.add(entry.usage()), "duplicate usage: " + entry.usage());
        }
    }

    @Test
    @DisplayName("the copied list applies the prefix to every line and keeps the count")
    void copyableAppliesPrefix() {
        List<String> lines = CommandCatalog.copyable("!");
        assertEquals(CommandCatalog.count(), lines.size());
        for (String line : lines) {
            assertTrue(line.startsWith("!"), "line has no prefix: " + line);
            assertFalse(line.contains("  -  "), "copied line carries description padding: " + line);
        }
    }

    @Test
    @DisplayName("a changed prefix is reflected everywhere, including the block form")
    void prefixIsNotHardCoded() {
        String block = CommandCatalog.copyableBlock(".");
        assertTrue(block.startsWith("."), "block should start with the new prefix");
        assertFalse(block.contains("!"), "no stale prefix should survive");
        assertEquals(CommandCatalog.count(), block.split("\n").length);
    }

    @Test
    @DisplayName("a group lists only its own commands")
    void groupsPartitionEntries() {
        int total = 0;
        for (CommandCatalog.Group group : CommandCatalog.Group.values()) {
            for (CommandCatalog.Entry entry : CommandCatalog.inGroup(group)) {
                assertEquals(group, entry.group(), "entry filed under the wrong group");
            }
            total += CommandCatalog.inGroup(group).size();
        }
        assertEquals(CommandCatalog.count(), total, "every entry belongs to exactly one group");
    }

    @Test
    @DisplayName("a group resolves from its key or its title, in any case")
    void groupParsesFromKeyAndTitle() {
        assertEquals(CommandCatalog.Group.CHAT, CommandCatalog.Group.parse("chat"));
        assertEquals(CommandCatalog.Group.CHAT, CommandCatalog.Group.parse("  CHAT "));
        assertEquals(CommandCatalog.Group.CHAT, CommandCatalog.Group.parse("Chat & logging"));
        assertNull(CommandCatalog.Group.parse("nonsense"));
        assertNull(CommandCatalog.Group.parse(""));
        assertNull(CommandCatalog.Group.parse(null));
    }

    @Test
    @DisplayName("the group summary names every group that holds commands")
    void groupSummaryCoversGroups() {
        List<CommandCatalog.GroupSummary> summary = CommandCatalog.groupSummary();
        assertFalse(summary.isEmpty());
        for (CommandCatalog.GroupSummary row : summary) {
            assertTrue(row.count() > 0, "a summarised group must hold commands");
            assertEquals(CommandCatalog.inGroup(row.group()).size(), row.count());
        }
        for (CommandCatalog.Group group : CommandCatalog.Group.values()) {
            if (CommandCatalog.inGroup(group).isEmpty()) continue;
            boolean listed = summary.stream().anyMatch(row -> row.group() == group);
            assertTrue(listed, "group missing from summary: " + group.title());
        }
    }

    @Test
    @DisplayName("find matches the command word and ignores a pasted prefix")
    void findMatchesCommandWord() {
        assertFalse(CommandCatalog.find("log").isEmpty());
        assertEquals(CommandCatalog.find("log").size(), CommandCatalog.find("!log").size());
        assertEquals(CommandCatalog.find("log").size(), CommandCatalog.find("  log ").size());
        assertFalse(CommandCatalog.find("chat").isEmpty());
        assertTrue(CommandCatalog.find("no-such-command").isEmpty());
        assertTrue(CommandCatalog.find("").isEmpty());
        assertTrue(CommandCatalog.find(null).isEmpty());
    }

    @Test
    @DisplayName("the example is a real command under the given prefix")
    void exampleIsRunnable() {
        String example = CommandCatalog.example("!");
        assertTrue(example.startsWith("!"));
        // The example must name a command that the catalog also lists, or the hint is a lie.
        String word = example.substring(1).split(" ")[0];
        assertFalse(CommandCatalog.find(word).isEmpty(), "example names an unknown command: " + word);
    }
}
