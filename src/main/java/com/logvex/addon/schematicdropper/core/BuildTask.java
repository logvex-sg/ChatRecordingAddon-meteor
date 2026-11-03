package com.logvex.addon.schematicdropper.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One queued build.
 *
 * <p>A task owns a schematic, an origin, a zone selection and a progress summary. Tasks are queued
 * so several schematics can be built in sequence without re-importing each one.
 */
public final class BuildTask {
    /** Lifecycle of a queued task. */
    public enum Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED;

        @Override
        public String toString() {
            return switch (this) {
                case QUEUED -> "Queued";
                case RUNNING -> "Running";
                case COMPLETED -> "Completed";
                case FAILED -> "Failed";
                case CANCELLED -> "Cancelled";
            };
        }
    }

    private final String id;
    private final String name;
    private final Schematic schematic;
    private final BlockPos3 origin;
    private final long createdAt;
    private Status status = Status.QUEUED;
    private int placed;
    private int failed;
    private int total;

    public BuildTask(String id, String name, Schematic schematic, BlockPos3 origin, long createdAt) {
        this.id = id;
        this.name = name == null || name.isBlank() ? (schematic == null ? "task" : schematic.name()) : name;
        this.schematic = schematic;
        this.origin = origin == null ? new BlockPos3(0, 0, 0) : origin;
        this.createdAt = createdAt;
        this.total = schematic == null ? 0 : schematic.blockCount();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Schematic schematic() {
        return schematic;
    }

    public BlockPos3 origin() {
        return origin;
    }

    public long createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        if (status != null) this.status = status;
    }

    public int placed() {
        return placed;
    }

    public int failed() {
        return failed;
    }

    public int total() {
        return total;
    }

    public void update(int placed, int failed, int total) {
        this.placed = Math.max(0, placed);
        this.failed = Math.max(0, failed);
        this.total = Math.max(0, total);
    }

    public double percent() {
        return total <= 0 ? 0 : Math.min(100.0, (double) placed / total * 100.0);
    }

    /** A short one-line description for the queue list. */
    public String describe() {
        return name + " - " + status + " (" + placed + "/" + total + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
