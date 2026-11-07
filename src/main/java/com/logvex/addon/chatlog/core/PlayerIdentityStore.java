package com.logvex.addon.chatlog.core;

import com.logvex.addon.framework.utils.misc.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the {@link PlayerIdentityTracker} as a small JSON file next to the logs.
 *
 * <p>Persisting the index means a rename observed in an earlier session is still reported after a
 * restart. The write is atomic (temp file then move), and a file that cannot be read is treated as
 * empty rather than fatal: losing the index must never stop the addon from recording chat.
 */
public final class PlayerIdentityStore {
    private static final String FILE_NAME = "identities.json";

    private PlayerIdentityStore() {
    }

    /** The index file inside {@code root}. */
    public static Path fileIn(Path root) {
        return root == null ? null : root.resolve(FILE_NAME);
    }

    /** Loads the stored identities into {@code tracker}, replacing whatever it held. */
    public static void load(Path root, PlayerIdentityTracker tracker) {
        if (root == null || tracker == null) return;
        Path file = fileIn(root);
        if (file == null || !Files.isRegularFile(file)) return;

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return;
            List<?> raw = Json.parse(text, List.class);
            if (raw == null) return;

            List<PlayerIdentity> identities = new ArrayList<>();
            for (Object element : raw) {
                if (element instanceof Map<?, ?> map) {
                    PlayerIdentity identity = PlayerIdentity.fromFullMap(map);
                    if (identity != null) identities.add(identity);
                }
            }
            tracker.load(identities);
        } catch (IOException | RuntimeException e) {
            // A corrupt or unreadable file is ignored: the index simply starts empty.
        }
    }

    /** Writes the index atomically. Returns false when the write could not be completed. */
    public static boolean save(Path root, PlayerIdentityTracker tracker) {
        if (root == null || tracker == null) return false;
        Path file = fileIn(root);
        if (file == null) return false;

        try {
            Files.createDirectories(root);
            String json = Json.write(tracker.toMaps()).toString();
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
