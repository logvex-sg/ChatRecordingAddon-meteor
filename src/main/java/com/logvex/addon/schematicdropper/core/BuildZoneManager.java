package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a schematic into {@link BuildZone}s and tracks which zone is being built.
 *
 * <p>Zones are ordered bottom-up by default so a lower layer is always finished before the layer
 * above it, which is what makes gravity blocks and floor attachments safe. The manager is pure data:
 * it never touches the world.
 */
public final class BuildZoneManager {
    /** How zones are ordered for building. */
    public enum ZoneOrder {
        BOTTOM_UP,
        TOP_DOWN,
        INDEX;

        @Override
        public String toString() {
            return switch (this) {
                case BOTTOM_UP -> "Bottom Up";
                case TOP_DOWN -> "Top Down";
                case INDEX -> "Index";
            };
        }
    }

    private final List<BuildZone> zones = new ArrayList<>();
    private int activeIndex;

    /** Splits a schematic into boxes of at most {@code maxSize} blocks per axis. */
    public void split(Schematic schematic, int maxSize) {
        zones.clear();
        activeIndex = 0;
        if (schematic == null || !schematic.hasGeometry()) return;

        int step = Math.max(1, maxSize);
        int sx = schematic.sizeX();
        int sy = schematic.sizeY();
        int sz = schematic.sizeZ();

        int index = 0;
        for (int y = 0; y < sy; y += step) {
            for (int z = 0; z < sz; z += step) {
                for (int x = 0; x < sx; x += step) {
                    BlockPos3 min = new BlockPos3(x, y, z);
                    BlockPos3 max = new BlockPos3(
                        Math.min(x + step - 1, sx - 1),
                        Math.min(y + step - 1, sy - 1),
                        Math.min(z + step - 1, sz - 1));

                    List<BlockPos3> positions = new ArrayList<>();
                    for (Map.Entry<BlockPos3, BlockStateSpec> e : schematic.blocks().entrySet()) {
                        if (inBox(e.getKey(), min, max)) positions.add(e.getKey());
                    }
                    if (positions.isEmpty()) continue; // skip empty boxes entirely
                    zones.add(new BuildZone(index++, min, max, positions));
                }
            }
        }
    }

    private static boolean inBox(BlockPos3 p, BlockPos3 min, BlockPos3 max) {
        return p.x() >= min.x() && p.x() <= max.x()
            && p.y() >= min.y() && p.y() <= max.y()
            && p.z() >= min.z() && p.z() <= max.z();
    }

    /** Reorders the zones for building. The {@code index} field stays stable for the GUI. */
    public void order(ZoneOrder order) {
        switch (order == null ? ZoneOrder.BOTTOM_UP : order) {
            case BOTTOM_UP -> zones.sort(Comparator.comparingInt((BuildZone z) -> z.min().y())
                .thenComparingInt(z -> z.min().z()).thenComparingInt(z -> z.min().x()));
            case TOP_DOWN -> zones.sort(Comparator.comparingInt((BuildZone z) -> z.min().y()).reversed()
                .thenComparingInt(z -> z.min().z()).thenComparingInt(z -> z.min().x()));
            case INDEX -> zones.sort(Comparator.comparingInt(BuildZone::index));
        }
    }

    public List<BuildZone> zones() {
        return List.copyOf(zones);
    }

    public int zoneCount() {
        return zones.size();
    }

    public boolean isEmpty() {
        return zones.isEmpty();
    }

    public BuildZone active() {
        if (zones.isEmpty()) return null;
        return zones.get(Math.max(0, Math.min(activeIndex, zones.size() - 1)));
    }

    public int activeIndex() {
        return activeIndex;
    }

    /** Selects a zone by its stable index. @return false when no such zone exists */
    public boolean select(int index) {
        for (int i = 0; i < zones.size(); i++) {
            if (zones.get(i).index() == index) {
                activeIndex = i;
                return true;
            }
        }
        return false;
    }

    /** Moves to the next zone. @return false when the last zone was already active */
    public boolean next() {
        if (activeIndex + 1 >= zones.size()) return false;
        activeIndex++;
        return true;
    }

    /** The material requirement of the active zone, derived from the schematic. */
    public MaterialPlan zoneRequirement(Schematic schematic) {
        MaterialPlan plan = new MaterialPlan();
        BuildZone zone = active();
        if (schematic == null || zone == null) return plan;

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockPos3 pos : zone.positions()) {
            BlockStateSpec spec = schematic.at(pos);
            if (spec.isAir()) continue;
            counts.merge(spec.id(), 1, Integer::sum);
        }
        List<Requirement> requirements = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            requirements.add(new Requirement(e.getKey(), e.getKey(), e.getValue()));
        }
        plan.set(requirements);
        return plan;
    }
}
