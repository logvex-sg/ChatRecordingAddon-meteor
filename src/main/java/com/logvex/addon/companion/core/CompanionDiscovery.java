package com.logvex.addon.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes a running companion's address so a local client can find it with no typing.
 *
 * <p>The old way to connect was to read the token out of the module GUI and paste a URL like
 * {@code http://127.0.0.1:6767/?token=...} into a browser. That is a manual step per launch, and it
 * is easy to get wrong. Instead, the server writes a small file into the addon's data folder while
 * it runs and deletes it on stop. A dashboard reads that file and connects to whatever port the
 * server actually bound, with the token it actually uses.
 *
 * <p>The file holds a secret, so two things are deliberate:
 *
 * <ul>
 *   <li>It is written only when the server is reachable from loopback. A server bound to a routable
 *       address is exposed by the user's own choice, and a token on disk next to it is a wider risk
 *       than the same token on the machine that runs the game.</li>
 *   <li>It is deleted on a clean stop, so a stale file does not point a client at a dead port. A
 *       crash can still leave one behind, which is why the file carries the process start time and
 *       the port: a reader verifies the port is live before trusting the token.</li>
 * </ul>
 *
 * <p>The write is atomic (temp file plus move) so a client polling the file never reads a half
 * written token.
 */
public final class CompanionDiscovery {
    /** The file name, inside the addon data folder. */
    public static final String FILE_NAME = "companion.json";

    /** The document version, so a future build can migrate rather than guess. */
    public static final int VERSION = 1;

    private final Path file;
    private final long startedAtMs;
    private volatile String lastError = "";

    public CompanionDiscovery(Path file) {
        this.file = file;
        this.startedAtMs = System.currentTimeMillis();
    }

    public Path file() {
        return file;
    }

    /** The last write or delete failure, or an empty string. Never throws to the caller. */
    public String lastError() {
        return lastError;
    }

    /**
     * Writes the discovery document for a running server.
     *
     * @param host the loopback host a client should dial, e.g. {@code 127.0.0.1}
     * @param port the port actually bound
     * @param token the shared token
     * @return true when the file was written
     */
    public boolean publish(String host, int port, String token) {
        if (host == null || host.isBlank() || port <= 0) {
            lastError = "Nothing to publish: the server is not bound.";
            return false;
        }
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("version", VERSION);
            doc.put("host", host);
            doc.put("port", port);
            doc.put("token", token == null ? "" : token);
            doc.put("pid", ProcessHandle.current().pid());
            doc.put("startedAtMs", startedAtMs);
            doc.put("updatedAtMs", System.currentTimeMillis());

            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, CompanionJson.write(doc), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            lastError = "";
            return true;
        } catch (IOException | RuntimeException e) {
            lastError = "Could not publish the discovery file: " + e.getMessage();
            return false;
        }
    }

    /** Removes the discovery file. Safe when it does not exist. */
    public boolean withdraw() {
        try {
            boolean existed = Files.deleteIfExists(file);
            lastError = "";
            return existed;
        } catch (IOException | RuntimeException e) {
            lastError = "Could not remove the discovery file: " + e.getMessage();
            return false;
        }
    }

    /** True when a discovery file is present. */
    public boolean exists() {
        return Files.exists(file);
    }

    /** The base URL a client should use, without the token. */
    public static String baseUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }

    /** The base URL with the token attached, for a client that cannot send a header. */
    public static String urlWithToken(String host, int port, String token) {
        return token == null || token.isBlank()
            ? baseUrl(host, port)
            : baseUrl(host, port) + "/?token=" + token;
    }
}
