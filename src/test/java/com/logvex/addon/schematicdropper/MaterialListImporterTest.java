package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.MaterialListImporter;
import com.logvex.addon.schematicdropper.core.Requirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-file import tests. The registry-backed resolver means an entry is only accepted when the
 * id exists in the real Minecraft 1.21.1 item list, so these tests exercise the same validation
 * the addon performs in game.
 */
class MaterialListImporterTest {
    private final FixtureRegistry registry = new FixtureRegistry();

    private static List<String> read(String resource) {
        try (InputStream in = MaterialListImporterTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource " + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- format A

    @Test
    @DisplayName("parses id and quantity, keeping quantities above one stack")
    void parsesSimpleFormat() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of(
            "minecraft:quartz_pillar 3861",
            "minecraft:spruce_planks 3420",
            "minecraft:polished_blackstone_brick_stairs 3191",
            "minecraft:stripped_spruce_log 2872"
        ), registry);

        assertEquals(4, result.requirements().size());
        assertEquals(0, result.invalid().size());
        assertEquals("minecraft:quartz_pillar", result.requirements().get(0).itemId());
        assertEquals(3861, result.requirements().get(0).required());
        assertEquals(3861 + 3420 + 3191 + 2872, result.totalRequired());
    }

    @Test
    @DisplayName("supports tabs, variable whitespace, CRLF and blank lines")
    void handlesWhitespaceAndLineEndings() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of(
            "  minecraft:stone   64  ",
            "\tminecraft:oak_planks\t128",
            "",
            "   ",
            "# a comment",
            "// another comment",
            "minecraft:dirt 32\r"
        ), registry);

        assertEquals(3, result.requirements().size());
        assertEquals(0, result.invalid().size());
        assertEquals(64, result.requirements().get(0).required());
        assertEquals(128, result.requirements().get(1).required());
        assertEquals(32, result.requirements().get(2).required());
    }

    @Test
    @DisplayName("accepts an id without a namespace")
    void addsDefaultNamespace() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of("stone 10"), registry);
        assertEquals(1, result.requirements().size());
        assertEquals("minecraft:stone", result.requirements().get(0).itemId());
    }

    @Test
    @DisplayName("aggregates duplicate ids onto their first occurrence and keeps order")
    void mergesDuplicates() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of(
            "minecraft:stone 10",
            "minecraft:dirt 20",
            "minecraft:stone 5"
        ), registry);

        assertEquals(2, result.requirements().size());
        assertEquals(1, result.duplicateIdsMerged());
        assertEquals("minecraft:stone", result.requirements().get(0).itemId());
        assertEquals(15, result.requirements().get(0).required());
        assertEquals("minecraft:dirt", result.requirements().get(1).itemId());
    }

    @Test
    @DisplayName("reports ids that do not exist in the item registry")
    void reportsUnknownIds() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of(
            "minecraft:not_a_real_item 10",
            "minecraft:stone 10"
        ), registry);

        assertEquals(1, result.requirements().size());
        assertEquals(1, result.invalid().size());
        assertEquals(1, result.invalid().get(0).lineNumber());
    }

    // ---------------------------------------------------------------- format B

    @Test
    @DisplayName("parses the material table and ignores the stack breakdown")
    void parsesTableFormat() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of(
            "| Material | Quantity |",
            "|---|---|",
            "| quartz pillar | 3861 (60 stacks + 21) |",
            "| spruce planks | 3420 (53 stacks + 28) |"
        ), registry);

        assertEquals(2, result.requirements().size());
        assertEquals("minecraft:quartz_pillar", result.requirements().get(0).itemId());
        assertEquals(3861, result.requirements().get(0).required());
        assertEquals(3420, result.requirements().get(1).required());
    }

    @Test
    @DisplayName("handles thousands separators")
    void handlesThousandsSeparators() {
        assertEquals(3861, MaterialListImporter.parseTableQuantity("3,861 (60 stacks + 21)"));
        assertEquals(1024, MaterialListImporter.parseTableQuantity("1 024"));
        assertEquals(12000, MaterialListImporter.parseTableQuantity("12'000"));
        assertEquals(999, MaterialListImporter.parseTableQuantity("999"));
        assertEquals(-1, MaterialListImporter.parseTableQuantity("stacks + 21"));
        assertEquals(-1, MaterialListImporter.parseTableQuantity(""));
    }

    // ---------------------------------------------------------------- sections

    @Test
    @DisplayName("imports only the Material List section of a multi-section report")
    void filtersSections() {
        MaterialListImporter.Result result =
            MaterialListImporter.parse(read("/material-report-sections.txt"), registry);

        assertTrue(result.requirements().size() > 300, "expected the whole material list");
        for (Requirement r : result.requirements()) {
            assertFalse(r.itemId().equals("minecraft:iron_ingot"), "raw resources must be excluded");
        }
        // The Choice Requirements section repeats stone and bricks; the report's own stone entry
        // keeps only its material-list quantity, and iron ingot from raw resources never appears.
        for (Requirement r : result.requirements()) {
            if (r.itemId().equals("minecraft:stone")) {
                assertEquals(64 + (5 * 137) % 3900, r.required());
            }
        }
    }

    @Test
    @DisplayName("a table without section headers still imports every row")
    void tableWithoutSections() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of(
            "| Material | Quantity |",
            "|---|---|",
            "| stone | 64 |",
            "| oak planks | 128 |"
        ), registry);

        assertEquals(2, result.requirements().size());
    }

    // ---------------------------------------------------------------- acceptance

    @Test
    @DisplayName("acceptance: the full 367-entry list is imported completely and in order")
    void importsFull367EntryList() {
        List<String> lines = read("/material-list-367.txt");
        MaterialListImporter.Result result = MaterialListImporter.parse(lines, registry);

        assertEquals(367, result.requirements().size(), "every entry must be read");
        assertEquals(0, result.invalid().size(), "the fixture contains only valid ids");
        assertEquals(0, result.duplicateIdsMerged());

        // Order and quantities must match the file exactly.
        List<String> expectedIds = new ArrayList<>();
        long expectedTotal = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+");
            expectedIds.add(parts[0]);
            expectedTotal += Long.parseLong(parts[1]);
        }
        assertEquals(367, expectedIds.size());
        assertEquals(expectedTotal, result.totalRequired());

        for (int i = 0; i < expectedIds.size(); i++) {
            assertEquals(expectedIds.get(i), result.requirements().get(i).itemId(),
                "order must be preserved at index " + i);
        }

        // First and last entries confirm the range was not truncated.
        assertEquals("minecraft:quartz_pillar", result.requirements().get(0).itemId());
        assertEquals(expectedIds.get(expectedIds.size() - 1),
            result.requirements().get(result.requirements().size() - 1).itemId());

        // The preview is capped but keeps the original order.
        List<Requirement> preview = result.preview(20);
        assertEquals(20, preview.size());
        assertEquals("minecraft:quartz_pillar", preview.get(0).itemId());
    }

    @Test
    @DisplayName("acceptance: the 367-entry table report keeps 367 unique materials")
    void importsFull367TableReport() {
        MaterialListImporter.Result result =
            MaterialListImporter.parse(read("/material-report-sections.txt"), registry);

        // The table report repeats no material, so it must import one entry per material row.
        assertEquals(367, result.requirements().size());
        assertEquals(0, result.invalid().size());
        assertEquals("minecraft:quartz_pillar", result.requirements().get(0).itemId());
    }

    @Test
    @DisplayName("the registry fixture is the real 1.21.1 item set")
    void registryFixtureLoaded() {
        assertTrue(FixtureRegistry.size() > 1000, "expected a full item registry");
        assertTrue(registry.isValidId("minecraft:quartz_pillar"));
        assertFalse(registry.isValidId("minecraft:not_a_real_item"));
        assertEquals("minecraft:quartz_pillar", registry.resolveName("quartz pillar"));
    }

    @Test
    @DisplayName("empty and null input produce an empty result")
    void emptyInput() {
        assertTrue(MaterialListImporter.parse(List.of(), registry).isEmpty());
        assertTrue(MaterialListImporter.parse(null, registry).isEmpty());
    }

    @Test
    @DisplayName("invalid quantities are reported rather than silently defaulted")
    void invalidQuantityInTable() {
        MaterialListImporter.Result result = MaterialListImporter.parse(List.of(
            "| Material | Quantity |",
            "|---|---|",
            "| stone | lots |"
        ), registry);

        assertEquals(0, result.requirements().size());
        assertEquals(1, result.invalid().size());
    }
}
