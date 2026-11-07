package com.logvex.addon.update.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Downloads a release asset to disk, streaming with a checksum and progress.
 *
 * <p>Nothing here is Minecraft-aware: it is given a URL and a destination and returns a
 * {@link DownloadResult}. The body is streamed to a temporary file so a large jar is never held in
 * memory, and the temporary file is moved into place only after it is complete, so an interrupted
 * download can never leave a half-written jar where a valid one is expected. When the release
 * metadata carries a SHA-256 the digest is verified before the file is accepted.
 */
public final class Downloader implements AssetFetcher {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int BUFFER = 16 * 1024;

    private final String userAgent;

    public Downloader(String userAgent) {
        this.userAgent = userAgent == null ? "Log Client" : userAgent;
    }

    /**
     * Downloads {@code asset} into {@code destinationDirectory}, verifying its checksum when the
     * release metadata provides one.
     */
    @Override
    public DownloadResult download(ReleaseAsset asset, Path destinationDirectory,
                                   ProgressListener listener) {
        if (asset == null || asset.browserDownloadUrl().isBlank()) {
            return DownloadResult.failed("The release has no downloadable file.");
        }

        Path target = destinationDirectory.resolve(safeName(asset.name()));
        Path temp = null;
        HttpURLConnection connection = null;
        try {
            Files.createDirectories(destinationDirectory);
            temp = Files.createTempFile(destinationDirectory, ".download-", ".part");

            connection = (HttpURLConnection) URI.create(asset.browserDownloadUrl()).toURL().openConnection();
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return DownloadResult.failed("The server returned HTTP " + status + ".");
            }

            long total = connection.getContentLengthLong();
            long expectedBytes = asset.size() > 0 ? asset.size() : total;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            long read = copy(connection.getInputStream(), temp, digest, expectedBytes, listener);

            if (expectedBytes > 0 && read != expectedBytes) {
                return DownloadResult.failed("The download was incomplete ("
                    + com.logvex.addon.common.Messages.bytes(read) + " of "
                    + com.logvex.addon.common.Messages.bytes(expectedBytes) + ").");
            }

            String actual = toHex(digest.digest());
            if (asset.hasChecksum() && !actual.equalsIgnoreCase(Checksums.normalise(asset.sha256()))) {
                return DownloadResult.failed("The checksum did not match; the file may be corrupted.");
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return DownloadResult.ok(target, actual, read);
        } catch (IOException e) {
            return DownloadResult.failed("Download failed: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            return DownloadResult.failed("SHA-256 is unavailable on this system.");
        } finally {
            if (connection != null) connection.disconnect();
            deleteQuietly(temp);
        }
    }

    private long copy(InputStream in, Path temp, MessageDigest digest, long total,
                      ProgressListener listener) throws IOException {
        long read = 0;
        byte[] buffer = new byte[BUFFER];
        try (OutputStream out = Files.newOutputStream(temp)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                read += n;
                if (listener != null) {
                    double fraction = total > 0 ? Math.min(1.0, (double) read / total) : -1;
                    listener.onProgress(fraction, read, total);
                }
            }
        }
        return read;
    }

    /** Keeps the file name from escaping the destination directory. */
    private static String safeName(String name) {
        String cleaned = name == null ? "" : name.trim().replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "update.jar" : cleaned;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover .part file is harmless; the next download creates a fresh one.
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
