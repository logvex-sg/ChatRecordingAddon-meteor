package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.common.Messages;
import com.logvex.addon.schematicdropper.core.ItemResolver;
import com.logvex.addon.schematicdropper.core.MaterialListImporter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a material list from disk and parses the whole file.
 *
 * <p>Reading happens in chunks over a caller-driven loop rather than in one blocking call, so a
 * large report can be imported without freezing the client. The parser never stops at a fixed
 * entry count: the file is read to EOF and every entry is kept.
 */
public final class SchematicFileImport {
    /** Chunk size for the progressive reader. */
    private static final int CHUNK_LINES = 500;

    private final Path path;
    private final ItemResolver resolver;
    private final List<String> lines = new ArrayList<>();

    private BufferedReader reader;
    private boolean finished;
    private MaterialListImporter.Result result;

    public SchematicFileImport(Path path, ItemResolver resolver) {
        this.path = path;
        this.resolver = resolver;
    }

    public Path path() {
        return path;
    }

    /**
     * Reads and parses up to {@link #CHUNK_LINES} more lines.
     *
     * @return {@code true} while more of the file still has to be read
     */
    public boolean step() throws IOException {
        if (finished) return false;

        if (reader == null) {
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }

        String line;
        int read = 0;
        while (read < CHUNK_LINES && (line = reader.readLine()) != null) {
            lines.add(line);
            read++;
        }

        if (read < CHUNK_LINES) {
            reader.close();
            finished = true;
            result = MaterialListImporter.parse(lines, resolver);
            return false;
        }
        return true;
    }

    public boolean isFinished() {
        return finished;
    }

    /** The parsed result. Only valid once {@link #step()} has returned {@code false}. */
    public MaterialListImporter.Result result() {
        return result;
    }

    /** Cancels the import and releases the reader. */
    public void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Nothing useful to do with a failure while closing a read-only file.
            }
            reader = null;
        }
    }

    /** One-line summary of an import result, phrased for chat. */
    public static String describe(MaterialListImporter.Result result, String fileName) {
        String merged = result.duplicateIdsMerged() == 0
            ? "no duplicates"
            : Messages.plural(result.duplicateIdsMerged(), "duplicate") + " merged";
        String invalid = result.invalid().isEmpty()
            ? "no invalid entries"
            : Messages.plural(result.invalid().size(), "invalid entry", "invalid entries");
        return Messages.format("Imported %s from %s: %s, %s, %s, %s.",
            Messages.plural(result.requirements().size(), "material"),
            fileName,
            Messages.number(result.totalRequired()) + " items",
            Messages.number(result.validEntries()) + " valid",
            merged,
            invalid);
    }
}
