package com.logvex.addon.schematicdropper.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for {@link Checkpoint}s.
 *
 * <p>Kept as an interface so the recovery logic is unit testable with an in-memory store, and so the
 * file implementation can live in the Minecraft adapter where disk access is allowed.
 */
public interface CheckpointStore {
    /** Writes a checkpoint atomically. */
    void save(Checkpoint checkpoint);

    /** The checkpoint for a schematic name, or empty when there is none. */
    Optional<Checkpoint> load(String schematicName);

    /** Every stored checkpoint, newest first. */
    List<Checkpoint> list();

    /** Removes the checkpoint for a schematic. */
    void delete(String schematicName);

    /** An in-memory store, used by tests and as a fallback. */
    static CheckpointStore inMemory() {
        return new CheckpointStore() {
            private final Map<String, Checkpoint> byName = new LinkedHashMap<>();

            @Override
            public void save(Checkpoint checkpoint) {
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
        };
    }
}
