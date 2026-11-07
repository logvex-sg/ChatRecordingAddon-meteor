package com.logvex.addon.automation;

import com.logvex.addon.automation.core.CreativeController;
import com.logvex.addon.automation.modules.AutoCreativeModule;
import com.logvex.addon.schematicdropper.core.MaterialListImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the whole import path reaches the acquisition queue: file bytes, parsed requirements,
 * {@code "id amount"} entries and finally the {@link CreativeController.Request} list the state
 * machine consumes. The fixture is the supplied 367-entry material list.
 */
class AutoCreativeImportTest {

    private static List<String> read(String resource) {
        try (InputStream in = AutoCreativeImportTest.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing fixture " + resource);
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The exact transformation the module performs before handing entries to the controller. */
    private static List<String> toEntries(MaterialListImporter.Result result) {
        List<String> entries = new ArrayList<>(result.requirements().size());
        for (var requirement : result.requirements()) {
            entries.add(requirement.itemId() + " " + requirement.required());
        }
        return entries;
    }

    @Test
    @DisplayName("All 367 valid entries of the supplied list reach the acquisition queue, in order")
    void fullListReachesQueue() {
        MaterialListImporter.Result result =
            MaterialListImporter.parse(read("/material-list-367.txt"),
                com.logvex.addon.schematicdropper.core.ItemResolver.lenient());

        List<CreativeController.Request> requests =
            AutoCreativeModule.parseItems(toEntries(result));

        assertEquals(367, requests.size(), "every valid entry must become a queue entry");
        assertEquals("minecraft:quartz_pillar", requests.get(0).itemId());
        assertEquals(64, requests.get(0).amount());
        // Order must be the file order, not sorted or re-grouped.
        for (int i = 1; i < requests.size(); i++) {
            assertEquals(result.requirements().get(i).itemId(), requests.get(i).itemId());
        }
        assertEquals(result.totalRequired(),
            requests.stream().mapToLong(CreativeController.Request::amount).sum());
    }

    @Test
    @DisplayName("The table report excludes raw resources and choice requirements")
    void tableReportExcludesOtherSections() {
        MaterialListImporter.Result result = MaterialListImporter.parse(
            read("/material-report-sections.txt"), com.logvex.addon.schematicdropper.core.ItemResolver.lenient());

        assertEquals(367, result.requirements().size());
        for (var requirement : result.requirements()) {
            String id = requirement.itemId();
            assertFalse(id.contains("raw_"), "raw resources must not be imported: " + id);
        }
    }

    @Test
    @DisplayName("parseItems tolerates whitespace, missing amounts and malformed lines")
    void parseItemsIsRobust() {
        List<CreativeController.Request> requests = AutoCreativeModule.parseItems(List.of(
            "  minecraft:stone   128  ",
            "minecraft:dirt",
            "minecraft:sand\t64",
            "",
            "   ",
            "minecraft:gravel notanumber",
            "minecraft:oak_log 0"));

        assertEquals(3, requests.size());
        assertEquals(128, requests.get(0).amount());
        assertEquals(1, requests.get(1).amount());
        assertEquals(64, requests.get(2).amount());
        assertTrue(requests.stream().noneMatch(r -> r.amount() <= 0));
    }

    @Test
    @DisplayName("Quantities larger than a stack survive the import unchanged")
    void largeQuantitiesPreserved() {
        List<CreativeController.Request> requests =
            AutoCreativeModule.parseItems(List.of("minecraft:quartz_pillar 3861"));
        assertEquals(1, requests.size());
        assertEquals(3861, requests.get(0).amount());
    }
}
