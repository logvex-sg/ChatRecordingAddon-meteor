package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved import configuration: where the file came from, how it was interpreted and what the
 * result was.
 *
 * <p>Profiles let a user re-import the same report later (for example after editing it) without
 * reconfiguring anything, and let the GUI show recent imports. They hold no Minecraft types and
 * serialise to plain JSON by the storage layer.
 */
public final class ImportProfile {
    private final String name;
    private final String sourceFile;
    private final SchematicFormat format;
    private final int entryCount;
    private final long totalQuantity;
    private final int invalidCount;
    private final long importedAt;

    public ImportProfile(String name, String sourceFile, SchematicFormat format,
                         int entryCount, long totalQuantity, int invalidCount, long importedAt) {
        this.name = name == null || name.isBlank() ? "import" : name;
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.format = format == null ? SchematicFormat.UNKNOWN : format;
        this.entryCount = Math.max(0, entryCount);
        this.totalQuantity = Math.max(0, totalQuantity);
        this.invalidCount = Math.max(0, invalidCount);
        this.importedAt = importedAt;
    }

    /** Builds a profile from a completed import. */
    public static ImportProfile of(String sourceFile, SchematicFormat format,
                                   MaterialListImporter.Result result, long importedAt) {
        int entries = result == null ? 0 : result.requirements().size();
        long total = result == null ? 0 : result.totalRequired();
        int invalid = result == null ? 0 : result.invalid().size();
        return new ImportProfile(sourceFile, sourceFile, format, entries, total, invalid, importedAt);
    }

    public String name() {
        return name;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public SchematicFormat format() {
        return format;
    }

    public int entryCount() {
        return entryCount;
    }

    public long totalQuantity() {
        return totalQuantity;
    }

    public int invalidCount() {
        return invalidCount;
    }

    public long importedAt() {
        return importedAt;
    }

    /** Keeps the newest profiles first and bounds the list so it cannot grow without limit. */
    public static List<ImportProfile> push(List<ImportProfile> existing, ImportProfile profile, int max) {
        List<ImportProfile> out = new ArrayList<>();
        if (profile != null) out.add(profile);
        if (existing != null) {
            for (ImportProfile p : existing) {
                if (p == null) continue;
                if (p.sourceFile().equals(profile == null ? "" : profile.sourceFile())) continue;
                out.add(p);
            }
        }
        int limit = Math.max(1, max);
        return List.copyOf(out.subList(0, Math.min(limit, out.size())));
    }
}
