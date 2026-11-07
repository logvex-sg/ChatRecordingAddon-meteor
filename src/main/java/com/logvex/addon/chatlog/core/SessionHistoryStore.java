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
 * Reads and writes the {@link SessionHistory} as a small JSON file next to the logs.
 *
 * <p>The write is atomic (a temp file is written then moved into place), so a crash mid-write can
 * never leave a truncated file that fails to load. A file that cannot be read is treated as empty
 * rather than fatal: losing the summary list must never stop the addon from recording chat.
 */
public final class SessionHistoryStore {
    private static final String FILE_NAME = "sessions.json";

    private SessionHistoryStore() {
    }

    /** The history file inside {@code root}. */
    public static Path fileIn(Path root) {
        return root == null ? null : root.resolve(FILE_NAME);
    }

    /** Loads the stored records into {@code history}, replacing whatever it held. */
    public static void load(Path root, SessionHistory history) {
        if (root == null || history == null) return;
        Path file = fileIn(root);
        if (file == null || !Files.isRegularFile(file)) return;

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) return;
            List<?> raw = Json.parse(text, List.class);
            if (raw == null) return;

            List<SessionRecord> records = new ArrayList<>();
            for (Object element : raw) {
                if (element instanceof Map<?, ?> map) {
                    SessionRecord record = SessionRecord.fromMap(map);
                    if (record != null) records.add(record);
                }
            }
            history.load(records);
        } catch (IOException | RuntimeException e) {
            // A corrupt or unreadable file is ignored: the in-memory history simply starts empty.
        }
    }

    /** Writes the history atomically. Returns false when the write could not be completed. */
    public static boolean save(Path root, SessionHistory history) {
        if (root == null || history == null) return false;
        Path file = fileIn(root);
        if (file == null) return false;

        try {
            Files.createDirectories(root);
            String json = Json.write(history.toMaps()).toString();
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
