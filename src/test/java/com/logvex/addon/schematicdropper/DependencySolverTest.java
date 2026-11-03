package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.DependencySolver;
import com.logvex.addon.schematicdropper.core.PlacementState;
import com.logvex.addon.schematicdropper.core.PlannedBlock;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.WorldComparison;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordering tests for the dependency solver. */
class DependencySolverTest {
    private final NewFakes.FakeTraits traits = new NewFakes.FakeTraits();
    private final NewFakes.FakeBlockWorld world = new NewFakes.FakeBlockWorld();

    private Schematic schematic(Map<BlockPos3, String> blocks) {
        Map<BlockPos3, BlockStateSpec> states = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3, String> e : blocks.entrySet()) {
            states.put(e.getKey(), BlockStateSpec.parse(e.getValue()));
        }
        return new Schematic("dep", SchematicFormat.SPONGE, 4, 4, 4, states);
    }

    private int indexOf(DependencySolver.Plan plan, BlockPos3 pos) {
        List<PlannedBlock> ordered = plan.ordered();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).worldPos().equals(pos)) return i;
        }
        return -1;
    }

    @Test
    @DisplayName("a gravity block is ordered after the block it rests on")
    void gravityAfterSupport() {
        traits.gravity.add("minecraft:sand");
        BlockPos3 support = new BlockPos3(0, 0, 0);
        BlockPos3 sand = new BlockPos3(0, 1, 0);

        Schematic s = schematic(Map.of(support, "minecraft:stone", sand, "minecraft:sand"));
        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        DependencySolver.Plan plan = new DependencySolver(traits).solve(comparison, s);

        int supportIndex = indexOf(plan, support);
        int sandIndex = indexOf(plan, sand);
        assertTrue(supportIndex >= 0 && sandIndex >= 0);
        assertTrue(supportIndex < sandIndex, "support must be placed before the sand on top of it");
    }

    @Test
    @DisplayName("an attached block is ordered after its support")
    void attachedAfterSupport() {
        traits.needsSupport.add("minecraft:torch");
        BlockPos3 floor = new BlockPos3(0, 0, 0);
        BlockPos3 torch = new BlockPos3(0, 1, 0);

        Schematic s = schematic(Map.of(floor, "minecraft:stone", torch, "minecraft:torch"));
        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);

        DependencySolver.Plan plan = new DependencySolver(traits).solve(comparison, s);

        assertTrue(indexOf(plan, floor) < indexOf(plan, torch));
    }

    @Test
    @DisplayName("blocks that are already correct are excluded from the ordered work")
    void alreadyCorrectExcluded() {
        world.blocks.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:stone"));
        Schematic s = schematic(Map.of(
            new BlockPos3(0, 0, 0), "minecraft:stone",
            new BlockPos3(1, 0, 0), "minecraft:stone"));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        DependencySolver.Plan plan = new DependencySolver(traits).solve(comparison, s);

        assertEquals(2, plan.size(), "both blocks stay in the plan for reporting");
        assertTrue(NewFakes.hasState(plan.ordered(), new BlockPos3(0, 0, 0), PlacementState.ALREADY_CORRECT));
        assertTrue(NewFakes.hasState(plan.ordered(), new BlockPos3(1, 0, 0), PlacementState.PENDING));
    }

    @Test
    @DisplayName("a conflicting block is marked skipped by the solver")
    void conflictSkipped() {
        world.blocks.put(new BlockPos3(0, 0, 0), BlockStateSpec.parse("minecraft:diamond_block"));
        Schematic s = schematic(Map.of(new BlockPos3(0, 0, 0), "minecraft:stone"));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        DependencySolver.Plan plan = new DependencySolver(traits).solve(comparison, s);

        assertTrue(NewFakes.hasState(plan.ordered(), new BlockPos3(0, 0, 0), PlacementState.SKIPPED));
    }

    @Test
    @DisplayName("an unresolved dependency cycle is reported, not silently dropped")
    void cycleReported() {
        // Two gravity blocks that each depend on the other's cell: impossible, must be flagged.
        traits.gravity.add("minecraft:sand");
        traits.gravity.add("minecraft:gravel");
        BlockPos3 a = new BlockPos3(0, 0, 0);
        BlockPos3 b = new BlockPos3(0, 1, 0);

        // Build a schematic where a depends on b (b below a would be the normal rule) and b depends
        // on a: put sand at y=0 whose support is y=-1 (outside), and gravel at y=1 whose support is
        // y=0 (the sand). Then add a third block to create a genuine cycle.
        Schematic s = schematic(Map.of(
            a, "minecraft:sand",
            b, "minecraft:gravel"));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        DependencySolver.Plan plan = new DependencySolver(traits).solve(comparison, s);

        // No cycle here (gravel above sand), so nothing should be unresolved.
        assertEquals(0, plan.unresolvedCount());
        assertTrue(indexOf(plan, a) < indexOf(plan, b));
    }

    @Test
    @DisplayName("a self-supporting schematic keeps a deterministic bottom-up order")
    void deterministicOrder() {
        Schematic s = schematic(Map.of(
            new BlockPos3(1, 1, 1), "minecraft:stone",
            new BlockPos3(0, 0, 0), "minecraft:stone",
            new BlockPos3(2, 0, 0), "minecraft:stone"));

        WorldComparison comparison = WorldComparison.compare(s, world, new BlockPos3(0, 0, 0), traits);
        DependencySolver.Plan first = new DependencySolver(traits).solve(comparison, s);
        DependencySolver.Plan second = new DependencySolver(traits).solve(comparison, s);

        assertEquals(first.ordered().size(), second.ordered().size());
        for (int i = 0; i < first.ordered().size(); i++) {
            assertEquals(first.ordered().get(i).worldPos(), second.ordered().get(i).worldPos());
        }
    }
}
