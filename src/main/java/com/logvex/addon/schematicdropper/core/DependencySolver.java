package com.logvex.addon.schematicdropper.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out a valid placement order for a schematic.
 *
 * <p>Not every block can be placed in an arbitrary order. This solver derives, for each block, the
 * positions it depends on, and produces an order in which every dependency comes first:
 *
 * <ul>
 *   <li><b>Gravity blocks</b> (sand, gravel, concrete powder) depend on the block below them, so
 *       they are placed after their support or they would fall.</li>
 *   <li><b>Attached blocks</b> (torch, flower, rail, redstone wire, carpet) depend on the face they
 *       hang from: the block below for a floor attachment, or a solid neighbour for a wall one.</li>
 *   <li><b>Directional blocks</b> (stairs, doors, trapdoors, fences, panes) do not depend on
 *       neighbours for support, but the engine still orders them after their support so the
 *       placement ray reaches the right face.</li>
 * </ul>
 *
 * <p>A dependency cycle (two gravity blocks that each "support" the other) is broken by falling
 * back to the deterministic bottom-up order and marking the block with a note, rather than failing
 * or retrying forever.
 */
public final class DependencySolver {
    private final BlockTraits traits;

    public DependencySolver(BlockTraits traits) {
        this.traits = traits == null ? null : traits;
    }

    /** The plan produced by the solver. */
    public record Plan(List<PlannedBlock> ordered, int unresolvedCount, List<String> notes) {
        public boolean isEmpty() {
            return ordered.isEmpty();
        }

        public int size() {
            return ordered.size();
        }
    }

    /**
     * Builds a placement order for the given comparison.
     *
     * @param comparison  the world comparison (which blocks already exist are excluded from work)
     * @param insideSchematic positions the schematic itself occupies; a dependency on one of these
     *                       is a real ordering constraint, a dependency outside is external
     */
    public Plan solve(WorldComparison comparison, Schematic schematic) {
        if (comparison == null || comparison.entries().isEmpty()) {
            return new Plan(List.of(), 0, List.of());
        }

        Set<BlockPos3> occupied = new HashSet<>();
        if (schematic != null) occupied.addAll(schematic.blocks().keySet());

        // Only blocks that actually need placing participate; already-correct ones are emitted as-is.
        Map<BlockPos3, PlannedBlock> work = new LinkedHashMap<>();
        List<PlannedBlock> alreadyDone = new ArrayList<>();
        for (WorldComparison.Entry entry : comparison.entries()) {
            BlockCategory category = categoryOf(entry.target());
            int dependencies = dependencyCount(entry, occupied);
            if (entry.status() == ComparisonStatus.CORRECT) {
                alreadyDone.add(new PlannedBlock(entry.schematicPos(), entry.worldPos(), entry.target(),
                    PlacementState.ALREADY_CORRECT, category, 0, "already present"));
            } else if (entry.status() == ComparisonStatus.CONFLICT) {
                alreadyDone.add(new PlannedBlock(entry.schematicPos(), entry.worldPos(), entry.target(),
                    PlacementState.SKIPPED, category, 0, "conflict with existing block"));
            } else if (entry.status() == ComparisonStatus.UNKNOWN) {
                alreadyDone.add(new PlannedBlock(entry.schematicPos(), entry.worldPos(), entry.target(),
                    PlacementState.BLOCKED, category, 0, "chunk not loaded"));
            } else {
                work.put(entry.worldPos(), new PlannedBlock(entry.schematicPos(), entry.worldPos(),
                    entry.target(), PlacementState.PENDING, category, dependencies, null));
            }
        }

        List<String> notes = new ArrayList<>();
        List<PlannedBlock> ordered = topologicalOrder(work, occupied, notes);

        List<PlannedBlock> result = new ArrayList<>(ordered.size() + alreadyDone.size());
        result.addAll(ordered);
        result.addAll(alreadyDone);

        int unresolved = 0;
        for (PlannedBlock b : result) if (b.state() == PlacementState.UNRESOLVED) unresolved++;
        return new Plan(List.copyOf(result), unresolved, List.copyOf(notes));
    }

    private int dependencyCount(WorldComparison.Entry entry, Set<BlockPos3> occupied) {
        int count = 0;
        for (BlockPos3 dep : dependenciesOf(entry.target(), entry.worldPos())) {
            if (occupied.contains(toSchematic(dep, entry))) count++;
        }
        return count;
    }

    private static BlockPos3 toSchematic(BlockPos3 worldDep, WorldComparison.Entry entry) {
        return entry.schematicPos().offset(
            worldDep.x() - entry.worldPos().x(),
            worldDep.y() - entry.worldPos().y(),
            worldDep.z() - entry.worldPos().z());
    }

    /**
     * The positions a block must have in place before it can be placed. Delegates to the shared
     * definition so planning and the runtime check cannot diverge.
     */
    private List<BlockPos3> dependenciesOf(BlockStateSpec target, BlockPos3 worldPos) {
        return PlacementDependencies.of(target, worldPos, traits);
    }

    private boolean isGravity(String id) {
        return PlacementDependencies.isGravity(id, traits);
    }

    private boolean isFloorAttached(String id) {
        return PlacementDependencies.isFloorAttached(id, traits);
    }

    private boolean isWallAttached(String id) {
        return PlacementDependencies.isWallAttached(id);
    }

    private BlockCategory categoryOf(BlockStateSpec target) {
        if (target == null) return BlockCategory.AIR;
        if (traits == null) return BlockCategory.SIMPLE;
        return traits.category(target.id());
    }

    /**
     * Kahn's algorithm over the dependency graph, with a deterministic tie-break so the order is
     * reproducible. A node that never becomes ready (a cycle) is appended at the end and marked
     * {@link PlacementState#UNRESOLVED} with an explanatory note.
     */
    private List<PlannedBlock> topologicalOrder(Map<BlockPos3, PlannedBlock> work,
                                                Set<BlockPos3> occupied, List<String> notes) {
        Map<BlockPos3, List<BlockPos3>> deps = new HashMap<>();
        Map<BlockPos3, List<BlockPos3>> dependents = new HashMap<>();

        for (PlannedBlock block : work.values()) {
            List<BlockPos3> own = new ArrayList<>();
            for (BlockPos3 dep : dependenciesOf(block.target(), block.worldPos())) {
                if (!work.containsKey(dep)) continue; // already correct or outside the schematic
                own.add(dep);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(block.worldPos());
            }
            deps.put(block.worldPos(), own);
        }

        Deque<BlockPos3> ready = new ArrayDeque<>();
        for (BlockPos3 pos : work.keySet()) {
            if (deps.getOrDefault(pos, List.of()).isEmpty()) ready.add(pos);
        }

        List<PlannedBlock> out = new ArrayList<>(work.size());
        Set<BlockPos3> emitted = new HashSet<>();
        while (!ready.isEmpty()) {
            BlockPos3 pos = ready.poll();
            if (!emitted.add(pos)) continue;
            out.add(work.get(pos).withState(PlacementState.PENDING, null));
            for (BlockPos3 dependent : dependents.getOrDefault(pos, List.of())) {
                if (emitted.contains(dependent)) continue;
                if (allDependenciesEmitted(dependent, deps, emitted)) ready.add(dependent);
            }
        }

        if (emitted.size() < work.size()) {
            // Remaining nodes are in a cycle; emit them in deterministic order and flag them.
            List<BlockPos3> remaining = new ArrayList<>();
            for (BlockPos3 pos : work.keySet()) if (!emitted.contains(pos)) remaining.add(pos);
            remaining.sort(null);
            for (BlockPos3 pos : remaining) {
                notes.add("Unresolved dependency cycle at " + pos + " (" + work.get(pos).target().id() + ")");
                out.add(work.get(pos).withState(PlacementState.UNRESOLVED,
                    "dependency cycle; place its support manually"));
            }
        }

        return out;
    }

    private static boolean allDependenciesEmitted(BlockPos3 pos, Map<BlockPos3, List<BlockPos3>> deps,
                                                  Set<BlockPos3> emitted) {
        for (BlockPos3 dep : deps.getOrDefault(pos, List.of())) {
            if (!emitted.contains(dep)) return false;
        }
        return true;
    }
}
