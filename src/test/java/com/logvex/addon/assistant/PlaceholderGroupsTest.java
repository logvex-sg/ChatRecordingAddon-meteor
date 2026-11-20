package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.Placeholders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderGroupsTest {

    @Test
    @DisplayName("every built-in placeholder lands in a named section")
    void everyPlaceholderIsGrouped() {
        Placeholders registry = Placeholders.standard();
        Map<String, List<String>> grouped = registry.byGroup();

        assertFalse(grouped.containsKey("Other"), "a placeholder was registered without a section");
        int counted = grouped.values().stream().mapToInt(List::size).sum();
        assertEquals(registry.names().size(), counted, "sections must account for every name");
    }

    @Test
    @DisplayName("sections appear in the order their placeholders were registered")
    void sectionsPreserveOrder() {
        Map<String, List<String>> grouped = Placeholders.standard().byGroup();
        assertEquals(List.of("Player", "Time", "Calendar", "Server", "Assistant", "Performance",
                "Client", "World", "Vitals", "Position", "Memory", "Inventory"),
            List.copyOf(grouped.keySet()));
    }

    @Test
    @DisplayName("a section holds exactly the names registered under it")
    void sectionMembership() {
        Map<String, List<String>> grouped = Placeholders.standard().byGroup();
        assertEquals(List.of("player", "message"), grouped.get("Player"));
        assertEquals(List.of("time", "worldtime", "day", "daytime"), grouped.get("Time"));
        assertEquals(List.of("date", "timestamp", "weekday", "month", "year"),
            grouped.get("Calendar"));
        assertEquals(List.of("uptime", "fps", "clientversion", "fabricversion", "modcount",
            "sessiontime"), grouped.get("Client"));
        assertEquals(List.of("memory", "name", "prefix", "factcount"),
            grouped.get("Assistant"));
        assertTrue(grouped.get("Position").containsAll(List.of("x", "y", "z", "coords")));
    }

    @Test
    @DisplayName("a copied registry keeps the section of every name")
    void copyKeepsGroups() {
        Placeholders original = Placeholders.standard();
        Placeholders clone = original.copy();
        for (String name : original.names()) {
            assertEquals(original.group(name), clone.group(name), name);
        }
    }

    @Test
    @DisplayName("an ungrouped name reports Other instead of throwing")
    void unknownNameIsOther() {
        assertEquals("Other", Placeholders.standard().group("not_a_placeholder"));
    }

    @Test
    @DisplayName("no section is so long that it floods the chat")
    void sectionsStayReadable() {
        // Splitting the reference into sections was the point: one section must fit on a screen.
        for (var entry : Placeholders.standard().byGroup().entrySet()) {
            assertTrue(entry.getValue().size() <= 8,
                entry.getKey() + " has " + entry.getValue().size() + " entries");
        }
    }
}
