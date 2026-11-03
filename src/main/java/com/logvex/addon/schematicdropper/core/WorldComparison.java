package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The result of comparing a schematic with the live world.
 *
 * <p>Holds one entry per schematic block with its {@link ComparisonStatus}, plus tallies the GUI
 * and HUD display. Building it is a pure function of a {@link Schematic}, a {@link BlockWorld} and
 * an origin, so it is fully unit testable.
 */
public final class WorldComparison {
    private final List<Entry> entries;
    private final Map<ComparisonStatus, Integer> counts;

    /** One compared block. */
    public record Entry(BlockPos3 schematicPos, BlockPos3 worldPos, BlockStateSpec target,
                        BlockStateSpec found, ComparisonStatus status) {
        public boolean isConflict() {
            return status == ComparisonStatus.CONFLICT;
        }
    }

    public WorldComparison(List<Entry> entries) {
        this.entries = List.copyOf(entries == null ? List.of() : entries);
        Map<ComparisonStatus, Integer> map = new EnumMap<>(ComparisonStatus.class);
        for (ComparisonStatus status : ComparisonStatus.values()) map.put(status, 0);
        for (Entry e : this.entries) map.merge(e.status(), 1, Integer::sum);
        this.counts = map;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int count(ComparisonStatus status) {
        return counts.getOrDefault(status, 0);
    }

    public int total() {
        return entries.size();
    }

    /** Blocks that still need to be placed (missing or incorrect). */
    public int needingWork() {
        return count(ComparisonStatus.MISSING) + count(ComparisonStatus.INCORRECT);
    }

    public List<Entry> withStatus(ComparisonStatus status) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.status() == status) out.add(e);
        return out;
    }

    public static WorldComparison empty() {
        return new WorldComparison(List.of());
    }

    /**
     * Compares a schematic against a world.
     *
     * @param origin the world position of the schematic's {@code (0,0,0)} corner
     */
    public static WorldComparison compare(Schematic schematic, BlockWorld world, BlockPos3 origin, BlockTraits traits) {
        if (schematic == null || !schematic.hasGeometry()) return empty();
        BlockWorld w = world == null ? BlockWorld.empty() : world;
        BlockTraits t = traits == null ? BlockTraitsStub.INSTANCE : traits;
        BlockPos3 base = origin == null ? new BlockPos3(0, 0, 0) : origin;

        List<Entry> entries = new ArrayList<>(schematic.blockCount());
        for (BlockPos3 local : schematic.orderedPositions()) {
            BlockStateSpec target = schematic.at(local);
            BlockPos3 worldPos = base.offset(local.x(), local.y(), local.z());

            if (!w.isLoaded(worldPos)) {
                entries.add(new Entry(local, worldPos, target, BlockStateSpec.air(), ComparisonStatus.UNKNOWN));
                continue;
            }

            BlockStateSpec found = w.stateAt(worldPos);
            ComparisonStatus status = classify(target, found, world, worldPos, t);
            entries.add(new Entry(local, worldPos, target, found, status));
        }
        return new WorldComparison(entries);
    }

    private static ComparisonStatus classify(BlockStateSpec target, BlockStateSpec found,
                                             BlockWorld world, BlockPos3 worldPos, BlockTraits traits) {
        if (found == null) found = BlockStateSpec.air();

        if (target.id().equals(found.id())) {
            // Same block: correct only when every property the schematic declares also matches.
            return target.matches(found) ? ComparisonStatus.CORRECT : ComparisonStatus.INCORRECT;
        }

        if (found.isAir()) return ComparisonStatus.MISSING;

        if (traits.isReplaceable(found.id())) {
            // Snow layers, grass, tall grass and similar: not a structure, safe to overwrite.
            return ComparisonStatus.MISSING;
        }

        return ComparisonStatus.CONFLICT;
    }

    /**
     * A minimal trait set used when no real one is supplied, so the comparison still runs in tests
     * and headless contexts. It only knows about air.
     */
    private enum BlockTraitsStub implements BlockTraits {
        INSTANCE;

        @Override
        public boolean isAir(String blockId) {
            return blockId == null || blockId.equals("minecraft:air")
                || blockId.equals("minecraft:cave_air") || blockId.equals("minecraft:void_air");
        }

        @Override
        public boolean isFluid(String blockId) {
            return blockId != null && (blockId.endsWith("_water") || blockId.endsWith("_lava"));
        }

        @Override
        public boolean isReplaceable(String blockId) {
            return isAir(blockId) || (blockId != null && (
                blockId.equals("minecraft:grass_block") || blockId.equals("minecraft:tall_grass")
                    || blockId.equals("minecraft:short_grass") || blockId.equals("minecraft:snow")
                    || blockId.equals("minecraft:water") || blockId.equals("minecraft:lava")));
        }

        @Override
        public boolean isGravityAffected(String blockId) {
            return false;
        }

        @Override
        public boolean requiresSupport(String blockId) {
            return false;
        }

        @Override
        public boolean isDirectional(String blockId) {
            return false;
        }

        @Override
        public boolean isContainer(String blockId) {
            return false;
        }

        @Override
        public boolean isRedstoneComponent(String blockId) {
            return false;
        }

        @Override
        public boolean hasSpecialPlacement(String blockId) {
            return false;
        }

        @Override
        public boolean isValid(String blockId) {
            return blockId != null && blockId.contains(":");
        }

        @Override
        public BlockCategory category(String blockId) {
            if (isAir(blockId)) return BlockCategory.AIR;
            if (isFluid(blockId)) return BlockCategory.FLUID;
            return BlockCategory.SIMPLE;
        }

        @Override
        public String displayName(String blockId) {
            return com.logvex.addon.common.Messages.prettyId(blockId);
        }

        @Override
        public int stackLimit(String blockId) {
            return 64;
        }
    }
}
