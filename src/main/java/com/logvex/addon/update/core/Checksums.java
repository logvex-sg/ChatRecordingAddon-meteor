package com.logvex.addon.update.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * SHA-256 helpers for verifying a downloaded update.
 *
 * <p>The digest is computed streaming, so a large jar never has to be held in memory. A downloaded
 * file whose digest does not match the release metadata is refused rather than installed.
 */
public final class Checksums {
    private Checksums() {
    }

    /** The lower-case hex SHA-256 of a file. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /** True when {@code expected} is a plausible SHA-256 hex string. */
    public static boolean isWellFormed(String expected) {
        if (expected == null) return false;
        String value = expected.trim();
        if (value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * Compares an expected digest with the file's actual digest, ignoring case and an optional
     * {@code sha256:} prefix. Returns false when the expected value is not a valid digest, so a
     * malformed metadata field can never be read as a pass.
     */
    public static boolean matches(Path file, String expected) throws IOException {
        String normalised = normalise(expected);
        if (normalised == null) return false;
        return sha256(file).equalsIgnoreCase(normalised);
    }

    /** Strips a {@code sha256:} prefix and lower-cases, or null when the value is not a digest. */
    public static String normalise(String expected) {
        if (expected == null) return null;
        String value = expected.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(colon + 1).trim();
        value = value.toLowerCase(Locale.ROOT);
        return isWellFormed(value) ? value : null;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; this cannot happen in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
