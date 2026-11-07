package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.MaterialListImporter;
import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.Requirement;
import com.logvex.addon.schematicdropper.mc.SchematicFileImport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-level import tests: the whole file must be read, in order, with no fixed entry limit.
 */
class SchematicFileImportTest {
    private final FixtureRegistry registry = new FixtureRegistry();

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static MaterialListImporter.Result runToCompletion(SchematicFileImport task) throws IOException {
        int guard = 0;
        while (task.step()) {
            if (++guard > 10_000) throw new IllegalStateException("import never finished");
        }
        return task.result();
    }

    @Test
    @DisplayName("imports the whole 367-entry fixture file from disk")
    void importsFixtureFile(@TempDir Path dir) throws IOException {
        String content = Files.readString(
            Path.of("src/test/resources/material-list-367.txt"), StandardCharsets.UTF_8);
        Path file = write(dir, "materials.txt", content);

        MaterialListImporter.Result result = runToCompletion(new SchematicFileImport(file, registry));

        assertNotNull(result);
        assertEquals(367, result.requirements().size());
        assertEquals(0, result.invalid().size());
        assertEquals("minecraft:quartz_pillar", result.requirements().get(0).itemId());
    }

    @Test
    @DisplayName("a large file is read to EOF with no entry limit")
    void noEntryLimit(@TempDir Path dir) throws IOException {
        // 5,000 entries is far beyond any plausible "first few lines" shortcut.
        StringBuilder sb = new StringBuilder();
        List<String> ids = List.of("stone", "dirt", "oak_planks", "glass", "bricks");
        for (int i = 0; i < 5_000; i++) {
            sb.append("minecraft:").append(ids.get(i % ids.size())).append(' ').append(i + 1).append('\n');
        }
        Path file = write(dir, "large.txt", sb.toString());

        MaterialListImporter.Result result = runToCompletion(new SchematicFileImport(file, registry));

        // Five distinct ids, all quantities aggregated.
        assertEquals(5, result.requirements().size());
        long expected = 0;
        for (int i = 0; i < 5_000; i++) expected += i + 1;
        assertEquals(expected, result.totalRequired());
        assertEquals(4_995, result.duplicateIdsMerged());
    }

    @Test
    @DisplayName("CRLF files and blank lines are handled")
    void handlesCrlf(@TempDir Path dir) throws IOException {
        Path file = write(dir, "crlf.txt",
            "minecraft:stone 64\r\n\r\nminecraft:dirt 32\r\n");

        MaterialListImporter.Result result = runToCompletion(new SchematicFileImport(file, registry));

        assertEquals(2, result.requirements().size());
        assertEquals(64, result.requirements().get(0).required());
        assertEquals(32, result.requirements().get(1).required());
    }

    @Test
    @DisplayName("an unreadable file reports a failure instead of pretending to succeed")
    void missingFile(@TempDir Path dir) {
        SchematicFileImport task = new SchematicFileImport(dir.resolve("does-not-exist.txt"), registry);
        boolean threw = false;
        try {
            while (task.step()) {
                // keep reading
            }
        } catch (IOException e) {
            threw = true;
        }
        assertTrue(threw, "a missing file must surface an IOException");
        assertFalse(task.isFinished());
    }

    @Test
    @DisplayName("the import summary is a single readable line")
    void summaryIsReadable(@TempDir Path dir) throws IOException {
        Path file = write(dir, "small.txt", "minecraft:stone 64\nminecraft:nope 10\n");
        MaterialListImporter.Result result = runToCompletion(new SchematicFileImport(file, registry));

        String summary = SchematicFileImport.describe(result, "small.txt");

        assertTrue(summary.startsWith("[Log] "), "the summary must use the shared prefix");
        assertTrue(summary.contains("1 material"), summary);
        assertTrue(summary.contains("1 invalid entry"), summary);
        assertTrue(summary.contains("64 items"), summary);
    }

    @Test
    @DisplayName("validEntries counts accepted entries before duplicate merging")
    void validEntryCount(@TempDir Path dir) throws IOException {
        Path file = write(dir, "dupes.txt", "minecraft:stone 10\nminecraft:stone 5\nminecraft:dirt 1\n");
        MaterialListImporter.Result result = runToCompletion(new SchematicFileImport(file, registry));

        assertEquals(2, result.requirements().size());
        assertEquals(3, result.validEntries());
        assertEquals(1, result.duplicateIdsMerged());
        assertEquals(16, result.totalQuantity());
    }

    @Test
    @DisplayName("a requirement keeps its file order after import")
    void preservesOrder(@TempDir Path dir) throws IOException {
        Path file = write(dir, "ordered.txt",
            "minecraft:dirt 1\nminecraft:stone 1\nminecraft:glass 1\n");

        MaterialListImporter.Result result = runToCompletion(new SchematicFileImport(file, registry));

        List<String> ids = result.requirements().stream().map(Requirement::itemId).toList();
        assertEquals(List.of("minecraft:dirt", "minecraft:stone", "minecraft:glass"), ids);
    }

    @Test
    @DisplayName("acceptance: the whole imported file becomes the ordered acquisition queue")
    void importedFileDrivesTheAcquisitionQueue(@TempDir Path dir) throws IOException {
        String content = Files.readString(
            Path.of("src/test/resources/material-list-367.txt"), StandardCharsets.UTF_8);
        Path file = write(dir, "materials.txt", content);

        MaterialListImporter.Result result = runToCompletion(new SchematicFileImport(file, registry));

        // Exactly what SchematicDropperModule.applyImport does: every requirement becomes one plan
        // entry, in file order, with its imported quantity. This is the contract that the queue the
        // acquisition engine consumes is the file itself.
        MaterialPlan plan = new MaterialPlan();
        plan.set(result.requirements());

        assertEquals(367, plan.size(), "every valid entry must reach the queue");
        List<Requirement> queued = plan.requirements();
        for (int i = 0; i < result.requirements().size(); i++) {
            assertEquals(result.requirements().get(i).itemId(), queued.get(i).itemId(),
                "queue order must match the file order at index " + i);
            assertEquals(result.requirements().get(i).required(), queued.get(i).required(),
                "queued quantity must match the file at index " + i);
        }
        assertEquals(result.totalRequired(), plan.totalRequired());
    }
}
