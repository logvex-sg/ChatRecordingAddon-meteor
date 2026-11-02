package com.logvex.addon.chatrelay.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous Discord webhook delivery.
 *
 * <p>Runs on a daemon worker so the client thread is never blocked. The webhook URL is treated as a
 * secret: it is never written into report bodies, never logged and never included in error
 * messages. HTTP 429 responses are retried after the server-provided delay, clamped to a sane
 * maximum so a misbehaving endpoint cannot stall the worker.
 */
public final class WebhookSender {
    public interface Listener {
        void onResult(boolean success, String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_DELAY_MS = 5000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-chatrelay-webhook");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean busy;

    public boolean isBusy() {
        return busy;
    }

    public void sendAsync(String url, String payload, Listener listener) {
        if (busy) {
            if (listener != null) listener.onResult(false, "A report is already being delivered.");
            return;
        }
        busy = true;
        executor.submit(() -> {
            boolean success;
            String message;
            try {
                Result result = deliver(url, payload);
                success = result.success;
                message = result.message;
            } catch (Exception e) {
                success = false;
                message = describe(e);
            } finally {
                busy = false;
            }
            if (listener != null) listener.onResult(success, message);
        });
    }

    private Result deliver(String url, String payload) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) return new Result(false, "No webhook URL configured.");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new Result(false, "Webhook URL must start with http:// or https://.");
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "Logvex-LiveChatRelay");

                byte[] body = payload.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }

                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) return new Result(true, "Delivered (HTTP " + code + ").");

                if (code == 429) {
                    long delay = retryDelay(connection);
                    if (attempt < MAX_ATTEMPTS) {
                        Thread.sleep(delay);
                        continue;
                    }
                    return new Result(false, "Rate limited (HTTP 429).");
                }

                if (code >= 500 && attempt < MAX_ATTEMPTS) {
                    Thread.sleep(250L * attempt);
                    continue;
                }

                return new Result(false, "Webhook rejected the report (HTTP " + code + ").");
            } catch (IOException e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(250L * attempt);
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        return new Result(false, lastError == null ? "Delivery failed." : describe(lastError));
    }

    private long retryDelay(HttpURLConnection connection) {
        String header = connection.getHeaderField("Retry-After");
        if (header != null) {
            try {
                double seconds = Double.parseDouble(header.trim());
                return Math.min(MAX_RETRY_DELAY_MS, (long) (seconds * 1000));
            } catch (NumberFormatException ignored) {
                // Fall through to the default delay.
            }
        }
        return 1000;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return "Delivery failed: " + (message == null ? e.getClass().getSimpleName() : message);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** Waits until the worker has no delivery in flight; used by tests. */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (busy && System.currentTimeMillis() < deadline) Thread.sleep(10);
        return !busy;
    }

    private record Result(boolean success, String message) {
    }
}
