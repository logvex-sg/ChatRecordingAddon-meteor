package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, Minecraft-free schematic.
 *
 * <p>Blocks are stored relative to the schematic origin: {@code (0,0,0)} is the minimum corner.
 * The importer converts every supported file format into this one representation, so the
 * comparison, dependency, placement, checkpoint and preview layers never need to know which file
 * format the user supplied.
 */
public final class Schematic {
    private final String name;
    private final SchematicFormat format;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Map<BlockPos3, BlockStateSpec> blocks;

    public Schematic(String name, SchematicFormat format, int sizeX, int sizeY, int sizeZ,
                     Map<BlockPos3, BlockStateSpec> blocks) {
        this.name = name == null || name.isBlank() ? "schematic" : name;
        this.format = format == null ? SchematicFormat.UNKNOWN : format;
        this.sizeX = Math.max(0, sizeX);
        this.sizeY = Math.max(0, sizeY);
        this.sizeZ = Math.max(0, sizeZ);
        this.blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks == null ? Map.of() : blocks));
    }

    /** A schematic with no geometry, used for material-list-only imports. */
    public static Schematic empty(String name) {
        return new Schematic(name, SchematicFormat.MATERIAL_LIST, 0, 0, 0, Map.of());
    }

    public String name() {
        return name;
    }

    public SchematicFormat format() {
        return format;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    /** True when this schematic carries block geometry, not just a material list. */
    public boolean hasGeometry() {
        return !blocks.isEmpty();
    }

    public int blockCount() {
        return blocks.size();
    }

    public BlockStateSpec at(BlockPos3 pos) {
        BlockStateSpec spec = blocks.get(pos);
        return spec == null ? BlockStateSpec.air() : spec;
    }

    public BlockStateSpec at(int x, int y, int z) {
        return at(new BlockPos3(x, y, z));
    }

    public boolean contains(BlockPos3 pos) {
        return blocks.containsKey(pos);
    }

    public Map<BlockPos3, BlockStateSpec> blocks() {
        return blocks;
    }

    /** Positions in a deterministic bottom-up build order. */
    public List<BlockPos3> orderedPositions() {
        List<BlockPos3> out = new ArrayList<>(blocks.keySet());
        Collections.sort(out);
        return out;
    }

    /** Distinct block states, most frequent first. Used for reporting and previews. */
    public Map<BlockStateSpec, Integer> stateCounts() {
        Map<BlockStateSpec, Integer> counts = new LinkedHashMap<>();
        for (BlockStateSpec spec : blocks.values()) {
            counts.merge(spec, 1, Integer::sum);
        }
        List<Map.Entry<BlockStateSpec, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<BlockStateSpec, Integer>>comparingInt(Map.Entry::getValue)
            .reversed().thenComparing(e -> e.getKey().serialize()));
        Map<BlockStateSpec, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<BlockStateSpec, Integer> e : entries) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }

    /** Derives the material plan (one entry per distinct block) in first-seen order. */
    public MaterialPlan toMaterialPlan() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (BlockPos3 pos : orderedPositions()) {
            BlockStateSpec spec = blocks.get(pos);
            if (spec == null || spec.isAir()) continue;
            counts.merge(spec.id(), 1, Integer::sum);
            names.putIfAbsent(spec.id(), spec.id());
        }
        List<Requirement> requirements = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            requirements.add(new Requirement(e.getKey(), names.get(e.getKey()), e.getValue()));
        }
        MaterialPlan plan = new MaterialPlan();
        plan.set(requirements);
        return plan;
    }
}
