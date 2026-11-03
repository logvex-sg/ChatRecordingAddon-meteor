package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BuildZoneManager;
import com.logvex.addon.schematicdropper.core.BuildTask;
import com.logvex.addon.schematicdropper.core.FailureLog;
import com.logvex.addon.schematicdropper.core.ImportProfile;
import com.logvex.addon.schematicdropper.core.MaterialExport;
import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.MaterialProgress;
import com.logvex.addon.schematicdropper.core.MaterialSearch;
import com.logvex.addon.schematicdropper.core.MaterialValidator;
import com.logvex.addon.schematicdropper.core.PlacementFailure;
import com.logvex.addon.schematicdropper.core.PlacementState;
import com.logvex.addon.schematicdropper.core.PlannedBlock;
import com.logvex.addon.schematicdropper.core.BlockCategory;
import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.Requirement;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.SubstitutionProfile;
import com.logvex.addon.schematicdropper.core.TaskQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Material validation, substitution, export, search and the surrounding bookkeeping. */
class MaterialManagementTest {

    // ---------------------------------------------------------------- validator

    @Test
    @DisplayName("validation separates valid entries from unknown ids")
    void validatorSplitsValidAndInvalid() {
        MaterialValidator validator = new MaterialValidator(NewFakes.resolver(
            Set.of("minecraft:stone", "minecraft:dirt")));

        MaterialValidator.Report report = validator.validate(List.of(
            new Requirement("minecraft:stone", "Stone", 10),
            new Requirement("minecraft:not_a_block", "Nope", 5),
            new Requirement("minecraft:dirt", "Dirt", 0)));

        assertEquals(1, report.validCount());
        assertEquals(2, report.invalidCount());
        assertEquals(10, report.totalQuantity());
        assertTrue(report.hasProblems());
    }

    @Test
    @DisplayName("a validator with no registry accepts syntactically valid ids")
    void validatorLenient() {
        MaterialValidator validator = new MaterialValidator(null);
        MaterialValidator.Report report = validator.validate(List.of(
            new Requirement("minecraft:stone", "Stone", 1)));

        assertEquals(1, report.validCount());
        assertEquals(0, report.invalidCount());
    }

    // ------------------------------------------------------------ substitution

    @Test
    @DisplayName("substitutions rewrite ids and merge quantities that collide")
    void substitutionMerges() {
        SubstitutionProfile profile = SubstitutionProfile.empty("test")
            .with("minecraft:oak_planks", "minecraft:spruce_planks");

        MaterialPlan plan = new MaterialPlan();
        plan.set(List.of(
            new Requirement("minecraft:oak_planks", "Oak Planks", 30),
            new Requirement("minecraft:spruce_planks", "Spruce Planks", 20),
            new Requirement("minecraft:stone", "Stone", 5)));

        MaterialPlan result = profile.apply(plan);

        assertEquals(2, result.size());
        assertEquals(50, result.get("minecraft:spruce_planks").required());
        assertEquals(5, result.get("minecraft:stone").required());
        assertFalse(result.contains("minecraft:oak_planks"));
    }

    @Test
    @DisplayName("an empty profile leaves a plan untouched")
    void emptySubstitutionIsIdentity() {
        MaterialPlan plan = new MaterialPlan();
        plan.set(List.of(new Requirement("minecraft:stone", "Stone", 5)));

        assertEquals(5, SubstitutionProfile.empty("none").apply(plan).get("minecraft:stone").required());
    }

    // ------------------------------------------------------------------- export

    @Test
    @DisplayName("export renders a header and one entry per requirement in order")
    void exportRenders() {
        String text = MaterialExport.render(MaterialExport.Scope.REQUIRED, List.of(
            new Requirement("minecraft:stone", "Stone", 10),
            new Requirement("minecraft:dirt", "Dirt", 5)), List.of());

        assertTrue(text.startsWith("# Required materials"));
        assertTrue(text.contains("minecraft:stone 10"));
        assertTrue(text.contains("minecraft:dirt 5"));
        assertTrue(text.indexOf("minecraft:stone") < text.indexOf("minecraft:dirt"));
        assertTrue(text.contains("# total 15 items"));
    }

    @Test
    @DisplayName("exporting invalid entries lists each problem with its line")
    void exportInvalid() {
        String text = MaterialExport.render(MaterialExport.Scope.INVALID, List.of(),
            List.of(new MaterialValidator.Issue(7, "bad id", "unknown item id")));

        assertTrue(text.contains("line 7"));
        assertTrue(text.contains("unknown item id"));
    }

    @Test
    @DisplayName("exporting an empty invalid list says so explicitly")
    void exportInvalidEmpty() {
        String text = MaterialExport.render(MaterialExport.Scope.INVALID, List.of(), List.of());
        assertTrue(text.contains("No invalid entries"));
    }

    @Test
    @DisplayName("exported text round-trips through the importer parser")
    void exportRoundTrip() {
        String text = MaterialExport.render(MaterialExport.Scope.REQUIRED, List.of(
            new Requirement("minecraft:stone", "Stone", 128),
            new Requirement("minecraft:quartz_pillar", "Quartz Pillar", 3861)), List.of());

        // The exported format is the simplified log addon format, so every id line must parse back.
        List<String> parsed = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) parsed.add(parts[0] + "=" + parts[1]);
        }
        assertEquals(List.of("minecraft:stone=128", "minecraft:quartz_pillar=3861"), parsed);
    }

    // ------------------------------------------------------------------- search

    private MaterialProgress progress(String id, int required, int confirmed, int inventory) {
        return new MaterialProgress(id, id, required, confirmed,
            Math.max(0, required - confirmed), inventory, 0);
    }

    @Test
    @DisplayName("a text query matches the id or the display name")
    void searchQuery() {
        List<MaterialProgress> input = List.of(
            progress("minecraft:stone", 10, 0, 10),
            progress("minecraft:oak_planks", 10, 0, 0));

        List<MaterialProgress> result = MaterialSearch.apply(input, "plank", MaterialSearch.Filter.ALL, MaterialSearch.Sort.NAME);

        assertEquals(1, result.size());
        assertEquals("minecraft:oak_planks", result.get(0).itemId());
    }

    @Test
    @DisplayName("filters select missing, complete and inventory states")
    void searchFilters() {
        List<MaterialProgress> input = List.of(
            progress("minecraft:stone", 10, 10, 10),   // complete, in inventory
            progress("minecraft:dirt", 10, 0, 0));    // incomplete, nothing in inventory

        assertEquals(1, MaterialSearch.apply(input, "", MaterialSearch.Filter.COMPLETE, MaterialSearch.Sort.NAME).size());
        assertEquals(1, MaterialSearch.apply(input, "", MaterialSearch.Filter.INCOMPLETE, MaterialSearch.Sort.NAME).size());
        assertEquals(1, MaterialSearch.apply(input, "", MaterialSearch.Filter.NOT_IN_INVENTORY, MaterialSearch.Sort.NAME).size());
    }

    @Test
    @DisplayName("sorting by required puts the largest first")
    void searchSort() {
        List<MaterialProgress> input = List.of(
            progress("minecraft:a", 5, 0, 0),
            progress("minecraft:b", 50, 0, 0));

        List<MaterialProgress> result = MaterialSearch.apply(input, "", MaterialSearch.Filter.ALL, MaterialSearch.Sort.REQUIRED_DESC);

        assertEquals("minecraft:b", result.get(0).itemId());
    }

    @Test
    @DisplayName("filter and sort cycles wrap around")
    void filterCycles() {
        assertEquals(MaterialSearch.Filter.ALL, MaterialSearch.Filter.NOT_IN_INVENTORY.next());
        assertEquals(MaterialSearch.Sort.NAME, MaterialSearch.Sort.STATUS.next());
    }

    // -------------------------------------------------------------------- zones

    @Test
    @DisplayName("a schematic splits into non-empty zones that cover every block")
    void zonesCoverBlocks() {
        Map<BlockPos3, BlockStateSpec> states = new LinkedHashMap<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                states.put(new BlockPos3(x, 0, z), BlockStateSpec.parse("minecraft:stone"));
            }
        }
        Schematic s = new Schematic("zones", SchematicFormat.SPONGE, 4, 1, 4, states);

        BuildZoneManager manager = new BuildZoneManager();
        manager.split(s, 2);

        assertTrue(manager.zoneCount() > 0);
        int covered = 0;
        for (var zone : manager.zones()) covered += zone.blockCount();
        assertEquals(16, covered, "every block must belong to exactly one zone");
    }

    @Test
    @DisplayName("zones are ordered bottom-up by default")
    void zonesBottomUp() {
        Map<BlockPos3, BlockStateSpec> states = new LinkedHashMap<>();
        states.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:stone"));
        states.put(new BlockPos3(0, 3, 0), BlockStateSpec.parse("minecraft:stone"));
        Schematic s = new Schematic("zones", SchematicFormat.SPONGE, 1, 4, 1, states);

        BuildZoneManager manager = new BuildZoneManager();
        manager.split(s, 1);
        manager.order(BuildZoneManager.ZoneOrder.BOTTOM_UP);

        assertTrue(manager.zones().get(0).min().y() <= manager.zones().get(manager.zoneCount() - 1).min().y());
    }

    @Test
    @DisplayName("a zone's requirement counts only its own blocks")
    void zoneRequirement() {
        Map<BlockPos3, BlockStateSpec> states = new LinkedHashMap<>();
        states.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:stone"));
        states.put(new BlockPos3(1, 0, 0), BlockStateSpec.parse("minecraft:stone"));
        Schematic s = new Schematic("zones", SchematicFormat.SPONGE, 2, 1, 1, states);

        BuildZoneManager manager = new BuildZoneManager();
        manager.split(s, 4); // one zone holding both
        MaterialPlan plan = manager.zoneRequirement(s);

        assertEquals(2, plan.get("minecraft:stone").required());
    }

    // ---------------------------------------------------------------- task queue

    @Test
    @DisplayName("the task queue starts tasks in order and finishes them")
    void queueOrder() {
        TaskQueue queue = new TaskQueue(4);
        Schematic s = Schematic.empty("one");
        queue.enqueue("first", s, new BlockPos3(0, 0, 0), 1000);
        queue.enqueue("second", s, new BlockPos3(0, 0, 0), 1000);

        BuildTask first = queue.startNext();
        assertNotNull(first);
        assertEquals("first", first.name());
        assertEquals(BuildTask.Status.RUNNING, first.status());

        queue.finishActive(BuildTask.Status.COMPLETED);
        BuildTask second = queue.startNext();
        assertNotNull(second);
        assertEquals("second", second.name());
    }

    @Test
    @DisplayName("the task queue refuses to exceed its limit")
    void queueLimit() {
        TaskQueue queue = new TaskQueue(1);
        assertNotNull(queue.enqueue("one", Schematic.empty("x"), new BlockPos3(0, 0, 0), 0));
        assertNull(queue.enqueue("two", Schematic.empty("y"), new BlockPos3(0, 0, 0), 0));
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("a cancelled task is not started")
    void queueCancel() {
        TaskQueue queue = new TaskQueue(4);
        BuildTask task = queue.enqueue("cancel me", Schematic.empty("x"), new BlockPos3(0, 0, 0), 0);
        assertNotNull(task);
        assertTrue(queue.cancel(task.id()));
        assertNull(queue.startNext());
    }

    @Test
    @DisplayName("finished tasks can be pruned without touching queued ones")
    void queuePrune() {
        TaskQueue queue = new TaskQueue(4);
        BuildTask done = queue.enqueue("done", Schematic.empty("x"), new BlockPos3(0, 0, 0), 0);
        queue.enqueue("queued", Schematic.empty("y"), new BlockPos3(0, 0, 0), 0);
        assertNotNull(done);
        queue.cancel(done.id());

        assertEquals(1, queue.pruneFinished());
        assertEquals(1, queue.size());
        assertEquals("queued", queue.tasks().get(0).name());
    }

    // ---------------------------------------------------------------- failure log

    @Test
    @DisplayName("the failure log is newest-first and bounded")
    void failureLogBounded() {
        FailureLog log = new FailureLog(3);
        for (int i = 0; i < 5; i++) {
            log.record(new BlockPos3(i, 0, 0), "minecraft:stone", PlacementFailure.NO_SUPPORT, "no support", 1000 + i);
        }

        assertEquals(3, log.size());
        assertEquals(new BlockPos3(4, 0, 0), log.entries().get(0).pos());
    }

    @Test
    @DisplayName("only recoverable failures with a position are offered for retry")
    void failureLogRetryable() {
        FailureLog log = new FailureLog();
        log.record(new BlockPos3(0, 0, 0), "minecraft:stone", PlacementFailure.NO_SUPPORT, "no support", 1);
        log.record(new BlockPos3(1, 0, 0), "minecraft:stone", PlacementFailure.SERVER_REJECTED, "rejected", 2);

        List<FailureLog.Entry> retryable = log.retryable();
        assertEquals(1, retryable.size());
        assertEquals(PlacementFailure.SERVER_REJECTED, retryable.get(0).failure());
    }

    // ----------------------------------------------------------------- profiles

    @Test
    @DisplayName("import profiles keep the newest first and are bounded")
    void importProfiles() {
        ImportProfile a = new ImportProfile("a", "a.txt", SchematicFormat.MATERIAL_LIST, 10, 100, 0, 1);
        ImportProfile b = new ImportProfile("b", "b.txt", SchematicFormat.MATERIAL_LIST, 20, 200, 0, 2);

        List<ImportProfile> list = ImportProfile.push(List.of(a), b, 5);
        assertEquals(2, list.size());
        assertEquals("b.txt", list.get(0).sourceFile());

        // Re-importing the same file replaces the old entry rather than duplicating it.
        ImportProfile b2 = new ImportProfile("b", "b.txt", SchematicFormat.MATERIAL_LIST, 25, 250, 0, 3);
        List<ImportProfile> replaced = ImportProfile.push(list, b2, 5);
        assertEquals(2, replaced.size());
        assertEquals(25, replaced.get(0).entryCount());
    }

    @Test
    @DisplayName("a planned block keeps its position and target through a state change")
    void plannedBlockImmutable() {
        PlannedBlock block = new PlannedBlock(new BlockPos3(1, 2, 3), new BlockPos3(10, 20, 30),
            BlockStateSpec.parse("minecraft:stone"), PlacementState.PENDING, BlockCategory.SIMPLE, 0, null);

        PlannedBlock placed = block.withState(PlacementState.COMPLETED, null);

        assertEquals(PlacementState.PENDING, block.state());
        assertEquals(PlacementState.COMPLETED, placed.state());
        assertEquals(block.worldPos(), placed.worldPos());
        assertFalse(placed.needsPlacement());
    }
}
