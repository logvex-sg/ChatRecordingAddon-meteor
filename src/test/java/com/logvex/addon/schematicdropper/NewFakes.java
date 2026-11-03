package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockCategory;
import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.BlockTraits;
import com.logvex.addon.schematicdropper.core.BlockWorld;
import com.logvex.addon.schematicdropper.core.Checkpoint;
import com.logvex.addon.schematicdropper.core.CheckpointStore;
import com.logvex.addon.schematicdropper.core.PendingPlacement;
import com.logvex.addon.schematicdropper.core.PlacementAction;
import com.logvex.addon.schematicdropper.core.PlacementEnv;
import com.logvex.addon.schematicdropper.core.PlacementFailure;
import com.logvex.addon.schematicdropper.core.PlacementListener;
import com.logvex.addon.schematicdropper.core.PlacementRequest;
import com.logvex.addon.schematicdropper.core.PlacementState;
import com.logvex.addon.schematicdropper.core.PlacementStateMachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory doubles for the placement engine. They model the behaviour that matters for
 * correctness: the world only changes when the fake action decides to change it, and a handle can
 * report PENDING for several polls before it confirms, so the engine's verification path is
 * exercised for real.
 */
public final class NewFakes {
    private NewFakes() {
    }

    /** A mutable world plus player state. */
    public static final class FakePlacementEnv implements PlacementEnv {
        public final Map<BlockPos3, BlockStateSpec> world = new LinkedHashMap<>();
        public final Map<String, Integer> inventory = new LinkedHashMap<>();
        public final Set<BlockPos3> unloaded = new HashSet<>();
        public boolean canRun = true;
        public boolean canPlace = true;
        public boolean creative = true;
        public double range = 5.0;
        public double[] position = {0, 0, 0};
        /** Blocks the fake considers replaceable, in addition to air. */
        public final Set<String> replaceable = new HashSet<>(Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:snow"));

        @Override
        public boolean canRun() {
            return canRun;
        }

        @Override
        public boolean canPlace() {
            return canPlace;
        }

        @Override
        public BlockStateSpec stateAt(BlockPos3 pos) {
            return world.getOrDefault(pos, BlockStateSpec.air());
        }

        @Override
        public boolean isLoaded(BlockPos3 pos) {
            return !unloaded.contains(pos);
        }

        @Override
        public boolean isReplaceable(BlockPos3 pos) {
            BlockStateSpec spec = stateAt(pos);
            return spec.isAir() || replaceable.contains(spec.id());
        }

        @Override
        public boolean isAirAt(BlockPos3 pos) {
            return stateAt(pos).isAir();
        }

        @Override
        public int countOf(String itemId) {
            return inventory.getOrDefault(itemId, 0);
        }

        @Override
        public boolean isCreative() {
            return creative;
        }

        @Override
        public double[] playerPosition() {
            return position;
        }

        @Override
        public double distanceSquaredTo(BlockPos3 pos) {
            double dx = pos.x() - position[0];
            double dy = pos.y() - position[1];
            double dz = pos.z() - position[2];
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** A placement action that writes to the world after a configurable number of polls. */
    public static final class FakePlacementAction implements PlacementAction {
        /** How many polls a placement stays PENDING before it resolves. 0 confirms on the first poll. */
        public int pollsBeforeConfirm;
        /** When set, the next placements fail with this failure instead of succeeding. */
        public PlacementFailure nextFailure;
        public boolean rejectAll;
        public boolean returnNull;
        public int started;
        public final List<PlacementRequest> requests = new ArrayList<>();

        private final FakePlacementEnv env;

        public FakePlacementAction(FakePlacementEnv env) {
            this.env = env;
        }

        @Override
        public PendingPlacement begin(PlacementRequest request) {
            started++;
            requests.add(request);
            if (returnNull) return null;

            if (rejectAll || nextFailure != null) {
                PlacementFailure failure = nextFailure == null ? PlacementFailure.SERVER_REJECTED : nextFailure;
                nextFailure = null;
                return new Immediate(PendingPlacement.Status.REJECTED, failure);
            }
            return new Delayed(request);
        }

        @Override
        public void cancelPending() {
        }

        private final class Delayed implements PendingPlacement {
            private final PlacementRequest request;
            private int polls;

            Delayed(PlacementRequest request) {
                this.request = request;
            }

            @Override
            public Status poll() {
                polls++;
                if (polls <= pollsBeforeConfirm) return Status.PENDING;
                // Only now does the world change, modelling a server-confirmed placement.
                env.world.put(request.worldPos(), request.target());
                return Status.CONFIRMED;
            }

            @Override
            public PlacementFailure failure() {
                return PlacementFailure.NONE;
            }
        }
    }

    private record Immediate(Status status, PlacementFailure failure) implements PendingPlacement {
        @Override
        public Status poll() {
            return status;
        }
    }

    /** Records everything the engine reports, so tests can assert on user-facing output. */
    public static final class RecordingListener implements PlacementListener {
        public final List<PlacementStateMachine> states = new ArrayList<>();
        public final List<String> placed = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();
        public final List<String> conflicts = new ArrayList<>();
        public final List<String> feedback = new ArrayList<>();
        public boolean completed;

        @Override
        public void onStateChanged(PlacementStateMachine state) {
            states.add(state);
        }

        @Override
        public void onPlaced(BlockPos3 pos, BlockStateSpec state) {
            placed.add(pos + "=" + state.id());
        }

        @Override
        public void onFailed(BlockPos3 pos, PlacementFailure failure, String detail) {
            failures.add(pos + ":" + failure.name());
        }

        @Override
        public void onConflict(BlockPos3 pos, BlockStateSpec expected, BlockStateSpec found) {
            conflicts.add(pos + ":" + found.id());
        }

        @Override
        public void onCompleted(long placedCount, long failedCount, long elapsedMs) {
            completed = true;
        }

        @Override
        public void onFeedback(String message) {
            feedback.add(message);
        }
    }

    /** A world backed by a map, for comparison tests. */
    public static final class FakeBlockWorld implements BlockWorld {
        public final Map<BlockPos3, BlockStateSpec> blocks = new HashMap<>();
        public final Set<BlockPos3> unloaded = new HashSet<>();

        @Override
        public BlockStateSpec stateAt(BlockPos3 worldPos) {
            return blocks.getOrDefault(worldPos, BlockStateSpec.air());
        }

        @Override
        public boolean isReplaceable(BlockPos3 worldPos) {
            return stateAt(worldPos).isAir();
        }

        @Override
        public boolean isLoaded(BlockPos3 worldPos) {
            return !unloaded.contains(worldPos);
        }
    }

    /** Traits driven by explicit sets, so tests state exactly what a block is. */
    public static final class FakeTraits implements BlockTraits {
        public final Set<String> gravity = new HashSet<>();
        public final Set<String> needsSupport = new HashSet<>();
        public final Set<String> directional = new HashSet<>();
        public final Set<String> containers = new HashSet<>();
        public final Set<String> redstone = new HashSet<>();
        public final Set<String> special = new HashSet<>();
        public final Set<String> replaceable = new HashSet<>(Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:snow", "minecraft:water"));
        public final Set<String> known = new HashSet<>();

        @Override
        public boolean isAir(String blockId) {
            return blockId == null || blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air");
        }

        @Override
        public boolean isFluid(String blockId) {
            return blockId != null && (blockId.equals("minecraft:water") || blockId.equals("minecraft:lava"));
        }

        @Override
        public boolean isReplaceable(String blockId) {
            return isAir(blockId) || replaceable.contains(blockId);
        }

        @Override
        public boolean isGravityAffected(String blockId) {
            return gravity.contains(blockId);
        }

        @Override
        public boolean requiresSupport(String blockId) {
            return needsSupport.contains(blockId);
        }

        @Override
        public boolean isDirectional(String blockId) {
            return directional.contains(blockId);
        }

        @Override
        public boolean isContainer(String blockId) {
            return containers.contains(blockId);
        }

        @Override
        public boolean isRedstoneComponent(String blockId) {
            return redstone.contains(blockId);
        }

        @Override
        public boolean hasSpecialPlacement(String blockId) {
            return special.contains(blockId);
        }

        @Override
        public boolean isValid(String blockId) {
            return known.isEmpty() || known.contains(blockId);
        }

        @Override
        public BlockCategory category(String blockId) {
            if (isAir(blockId)) return BlockCategory.AIR;
            if (isFluid(blockId)) return BlockCategory.FLUID;
            if (gravity.contains(blockId)) return BlockCategory.GRAVITY;
            if (needsSupport.contains(blockId)) return BlockCategory.ATTACHED;
            return BlockCategory.SIMPLE;
        }

        @Override
        public String displayName(String blockId) {
            return blockId;
        }

        @Override
        public int stackLimit(String blockId) {
            return 64;
        }
    }

    /** A checkpoint store that keeps everything in memory and can be made to fail saves. */
    public static final class FakeCheckpointStore implements CheckpointStore {
        private final Map<String, Checkpoint> byName = new LinkedHashMap<>();
        public boolean failSaves;

        @Override
        public void save(Checkpoint checkpoint) {
            if (failSaves) throw new IllegalStateException("disk full");
            if (checkpoint != null) byName.put(checkpoint.schematicName(), checkpoint);
        }

        @Override
        public Optional<Checkpoint> load(String schematicName) {
            return Optional.ofNullable(byName.get(schematicName));
        }

        @Override
        public List<Checkpoint> list() {
            return List.copyOf(byName.values());
        }

        @Override
        public void delete(String schematicName) {
            byName.remove(schematicName);
        }
    }

    /** A simple id resolver for tests. */
    public static com.logvex.addon.schematicdropper.core.ItemResolver resolver(Set<String> valid) {
        return new com.logvex.addon.schematicdropper.core.ItemResolver() {
            @Override
            public boolean isValidId(String itemId) {
                return itemId != null && valid.contains(itemId);
            }

            @Override
            public String resolveName(String name) {
                return null;
            }
        };
    }

    /** Builds a schematic from {@code x,y,z=id} triples, all with the default state. */
    public static com.logvex.addon.schematicdropper.core.Schematic schematic(
        String name, int sx, int sy, int sz, Map<BlockPos3, String> blocks) {
        Map<BlockPos3, BlockStateSpec> states = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3, String> e : blocks.entrySet()) {
            states.put(e.getKey(), BlockStateSpec.parse(e.getValue()));
        }
        return new com.logvex.addon.schematicdropper.core.Schematic(
            name, com.logvex.addon.schematicdropper.core.SchematicFormat.SPONGE, sx, sy, sz, states);
    }

    /** True when the given plan contains a block at {@code pos} in the given state. */
    public static boolean hasState(List<com.logvex.addon.schematicdropper.core.PlannedBlock> plan,
                                   BlockPos3 pos, PlacementState state) {
        for (com.logvex.addon.schematicdropper.core.PlannedBlock b : plan) {
            if (b.worldPos().equals(pos) && b.state() == state) return true;
        }
        return false;
    }
}
