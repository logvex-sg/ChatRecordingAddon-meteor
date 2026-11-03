package com.logvex.addon.schematicdropper.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A persisted build snapshot, enough to resume an interrupted build safely.
 *
 * <p>A checkpoint records which positions were confirmed complete and which failed, the origin the
 * schematic was placed at and a fingerprint of the schematic. It deliberately does <b>not</b> record
 * "trust me, skip these": on load the recovery manager re-validates every position against the
 * world, because the world may have changed while the game was closed.
 */
public final class Checkpoint {
    /** Bump when the on-disk shape changes so old files can be migrated or rejected. */
    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final String schematicName;
    private final String schematicFingerprint;
    private final BlockPos3 origin;
    private final long createdAt;
    private final long updatedAt;
    private final Map<BlockPos3, String> completed;
    private final Map<BlockPos3, String> failed;
    private final int totalBlocks;
    private final long materialsPlaced;

    public Checkpoint(int version, String schematicName, String schematicFingerprint, BlockPos3 origin,
                      long createdAt, long updatedAt, Map<BlockPos3, String> completed,
                      Map<BlockPos3, String> failed, int totalBlocks, long materialsPlaced) {
        this.version = version;
        this.schematicName = schematicName == null ? "" : schematicName;
        this.schematicFingerprint = schematicFingerprint == null ? "" : schematicFingerprint;
        this.origin = origin == null ? new BlockPos3(0, 0, 0) : origin;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completed = new LinkedHashMap<>(completed == null ? Map.of() : completed);
        this.failed = new LinkedHashMap<>(failed == null ? Map.of() : failed);
        this.totalBlocks = Math.max(0, totalBlocks);
        this.materialsPlaced = Math.max(0, materialsPlaced);
    }

    /** Captures the current engine state as a checkpoint. */
    public static Checkpoint capture(String schematicName, String fingerprint, BlockPos3 origin,
                                     PlacementEngine engine, long nowMs) {
        Map<BlockPos3, String> done = new LinkedHashMap<>();
        Map<BlockPos3, String> failed = new LinkedHashMap<>();
        for (PlannedBlock b : engine.plan()) {
            if (b.state() == PlacementState.COMPLETED || b.state() == PlacementState.ALREADY_CORRECT) {
                done.put(b.worldPos(), b.target().serialize());
            } else if (b.state() == PlacementState.FAILED || b.state() == PlacementState.UNRESOLVED) {
                failed.put(b.worldPos(), b.note() == null ? "failed" : b.note());
            }
        }
        return new Checkpoint(CURRENT_VERSION, schematicName, fingerprint, origin, nowMs, nowMs,
            done, failed, engine.plan().size(), engine.snapshot().materialsPlaced());
    }

    public int version() {
        return version;
    }

    public String schematicName() {
        return schematicName;
    }

    public String schematicFingerprint() {
        return schematicFingerprint;
    }

    public BlockPos3 origin() {
        return origin;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public Map<BlockPos3, String> completed() {
        return Map.copyOf(completed);
    }

    public Map<BlockPos3, String> failed() {
        return Map.copyOf(failed);
    }

    public int totalBlocks() {
        return totalBlocks;
    }

    public long materialsPlaced() {
        return materialsPlaced;
    }

    public int completedCount() {
        return completed.size();
    }

    public int failedCount() {
        return failed.size();
    }

    public double percent() {
        return totalBlocks <= 0 ? 0 : Math.min(100.0, (double) completed.size() / totalBlocks * 100.0);
    }

    /** A copy with a fresh {@code updatedAt} and additional completed positions. */
    public Checkpoint withCompleted(Map<BlockPos3, String> extra, long nowMs) {
        Map<BlockPos3, String> next = new LinkedHashMap<>(completed);
        if (extra != null) next.putAll(extra);
        return new Checkpoint(version, schematicName, schematicFingerprint, origin, createdAt, nowMs,
            next, failed, totalBlocks, materialsPlaced);
    }

    /**
     * Basic integrity checks that do not need the world: a checkpoint written by a different
     * schematic, or with a corrupt shape, must never be resumed.
     *
     * @param expectedFingerprint the fingerprint of the schematic being resumed
     * @param expectedOrigin      the origin the current build is using
     */
    public Integrity validate(String expectedFingerprint, BlockPos3 expectedOrigin) {
        if (version <= 0 || version > CURRENT_VERSION) {
            return new Integrity(false, "checkpoint version " + version + " is not supported");
        }
        if (expectedFingerprint != null && !expectedFingerprint.isBlank()
            && !expectedFingerprint.equals(schematicFingerprint)) {
            return new Integrity(false, "the checkpoint belongs to a different schematic");
        }
        if (expectedOrigin != null && !expectedOrigin.equals(origin)) {
            return new Integrity(false, "the checkpoint was made at a different origin");
        }
        if (totalBlocks <= 0) {
            return new Integrity(false, "the checkpoint has no block count");
        }
        return new Integrity(true, "ok");
    }

    /** The result of {@link #validate}. */
    public record Integrity(boolean valid, String reason) {
    }

    /**
     * A stable fingerprint of a schematic's geometry, used to detect that a checkpoint belongs to a
     * different (or edited) file. Cheap to compute: a size plus a rolling hash of the positions.
     */
    public static String fingerprint(Schematic schematic) {
        if (schematic == null) return "";
        long hash = 1125899906842597L;
        hash = hash * 31 + schematic.sizeX();
        hash = hash * 31 + schematic.sizeY();
        hash = hash * 31 + schematic.sizeZ();
        List<BlockPos3> positions = schematic.orderedPositions();
        for (BlockPos3 p : positions) {
            hash = hash * 31 + p.x();
            hash = hash * 31 + p.y();
            hash = hash * 31 + p.z();
        }
        return Long.toHexString(hash) + "-" + positions.size();
    }
}
