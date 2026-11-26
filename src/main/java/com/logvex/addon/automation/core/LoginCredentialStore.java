package com.logvex.addon.automation.core;

import com.logvex.addon.framework.LogClientContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-server login passwords, stored in a file of their own.
 *
 * <p>Deliberately not a Meteor {@link meteordevelopment.meteorclient.settings.Setting}: Meteor
 * writes every setting into the module config, and that config is the file users copy between
 * machines, paste into issues and commit to repositories. A password there would leak the first
 * time anyone shared a config. Keeping it in {@code logclient/autologin.properties} means it is
 * never part of an exported or synced config, and the file is created owner-readable only.
 *
 * <p>The value is never returned for display. {@link #masked(String)} gives a status line safe to
 * print in chat, which a screenshot or a stream would expose.
 *
 * <p>Passwords are stored in plain text on disk. Obfuscating them would be theatre: the client has
 * to recover the original to send it, so any key it uses is on the same disk. File permissions and
 * keeping it out of shared configs are the protections that actually hold.
 */
public final class LoginCredentialStore {
    private static final String FILE_NAME = "autologin.properties";

    private final Path file;
    private final Map<String, String> entries = new LinkedHashMap<>();

    /** A store backed by {@code logclient/autologin.properties} under the addon folder. */
    public LoginCredentialStore() {
        this(LogClientContext.dataFolder().toPath().resolve(FILE_NAME));
    }

    public LoginCredentialStore(Path file) {
        this.file = file;
    }

    /** Loads from disk, replacing anything held in memory. A missing file is not an error. */
    public synchronized void load() {
        entries.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                entries.put(key(trimmed.substring(0, eq)), unescape(trimmed.substring(eq + 1)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
        registerForRedaction();
    }

    /**
     * Writes the current contents, creating the file owner-readable only.
     *
     * <p>The bytes go to a sibling file that is then renamed over the real one, so a crash or a full
     * disk can never leave a half-written password file: either the old file or the new one is on
     * disk, never a mixture. Writing in place would truncate first, and a failure after that point
     * would destroy every stored password.
     */
    public synchronized void save() {
        if (file == null) return;
        StringBuilder out = new StringBuilder();
        out.append("# Log Client auto-login passwords.\n");
        out.append("# Plain text: anyone with this file can read these. Do not share it.\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            out.append(entry.getKey()).append('=').append(escape(entry.getValue())).append('\n');
        }
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(temp);
            // The permissions are set when the file is created, not after: a file that appears
            // world-readable for even a moment has already exposed the passwords in it.
            createOwnerOnly(temp);
            Files.writeString(temp, out.toString(), StandardCharsets.UTF_8, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    /** The password for {@code server}, or null when none is stored. */
    public synchronized String get(String server) {
        return entries.get(key(server));
    }

    /**
     * Registers every stored password with the {@link com.logvex.addon.common.SecretRedactor} so a
     * line containing one is scrubbed before it is archived or relayed.
     */
    public synchronized void registerForRedaction() {
        for (String value : entries.values()) {
            com.logvex.addon.common.SecretRedactor.register(value);
        }
    }

    public synchronized boolean has(String server) {
        String value = entries.get(key(server));
        return value != null && !value.isEmpty();
    }

    public synchronized void put(String server, String password) {
        if (server == null || server.isBlank()) return;
        if (password == null || password.isEmpty()) {
            entries.remove(key(server));
            return;
        }
        entries.put(key(server), password);
        // Register immediately, so the value is redacted even before the next save and reload.
        com.logvex.addon.common.SecretRedactor.register(password);
    }

    public synchronized boolean remove(String server) {
        return entries.remove(key(server)) != null;
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** The servers a password is stored for, in insertion order. Never the passwords. */
    public synchronized Set<String> servers() {
        return Set.copyOf(entries.keySet());
    }

    public synchronized int size() {
        return entries.size();
    }

    /** The backing file, for a status line. */
    public Path file() {
        return file;
    }

    /** Normalises a server address so {@code Play.Example.com:25565} matches {@code play.example.com}. */
    public static String key(String server) {
        if (server == null) return "";
        String value = server.strip().toLowerCase(Locale.ROOT);
        if (value.endsWith(":25565")) value = value.substring(0, value.length() - 6);
        return value;
    }

    /**
     * A status-safe rendering of a stored password: its length only.
     *
     * <p>Never returns the value or a prefix of it. The point is to let a user confirm a password
     * is set without printing it into a chat window, a log or a screenshot.
     */
    public static String masked(String password) {
        if (password == null || password.isEmpty()) return "not set";
        return "set (" + password.length() + " characters)";
    }

    /** Creates an empty file that only the owner can read or write. */
    private static void createOwnerOnly(Path file) throws IOException {
        try {
            Files.createFile(file,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows): fall back to a plain create and the later restrict.
            Files.createFile(file);
        }
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows). The file still lives outside any shared config.
        }
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '=' -> out.append("\\=");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case '=' -> out.append('=');
                case '\\' -> out.append('\\');
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
