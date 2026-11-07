package com.logvex.addon.chatlog.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous webhook delivery with an internal queue.
 *
 * <p>Runs entirely on a daemon worker, so the client thread never blocks. When a request fails
 * transiently (network error, 5xx, 429) the payload is re-queued instead of dropped, so a temporary
 * outage does not lose alerts. The webhook URL is treated as a secret: it is never logged and never
 * included in a payload or an error message.
 *
 * <p>Delivery is opt-in: nothing is transmitted until a URL is configured.
 */
public final class WebhookQueue {
    public enum State {
        DISABLED,
        IDLE,
        SENDING,
        RETRYING,
        FAILED
    }

    public interface Listener {
        void onResult(boolean success, String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_DELAY_MS = 5000;
    private static final int MAX_QUEUE = 500;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "logvex-chatlog-webhook");
        thread.setDaemon(true);
        return thread;
    });

    private final Deque<Job> queue = new ArrayDeque<>();
    private volatile boolean running;
    private volatile State state = State.IDLE;
    private volatile String lastMessage = "No deliveries yet.";

    private volatile int delivered;
    private volatile int failed;
    private volatile int retries;

    /** Enqueues a payload for delivery. Returns false when the queue is full. */
    public synchronized boolean enqueue(String url, String payload, Listener listener) {
        if (url == null || url.isBlank()) {
            if (listener != null) listener.onResult(false, "No webhook URL configured.");
            return false;
        }
        if (queue.size() >= MAX_QUEUE) {
            if (listener != null) listener.onResult(false, "Webhook queue is full; notification dropped.");
            return false;
        }
        queue.addLast(new Job(url, payload, listener, 1));
        drain();
        return true;
    }

    public synchronized int queued() {
        return queue.size();
    }

    public State state() {
        return state;
    }

    public String lastMessage() {
        return lastMessage;
    }

    public int delivered() {
        return delivered;
    }

    public int failed() {
        return failed;
    }

    public int retries() {
        return retries;
    }

    private synchronized void drain() {
        if (running) return;
        running = true;
        executor.submit(this::runLoop);
    }

    private void runLoop() {
        while (true) {
            Job job;
            synchronized (this) {
                job = queue.pollFirst();
                if (job == null) {
                    running = false;
                    state = State.IDLE;
                    return;
                }
            }
            deliver(job);
        }
    }

    private void deliver(Job job) {
        state = State.SENDING;
        Result result;
        try {
            result = attempt(job.url, job.payload);
        } catch (Exception e) {
            result = new Result(false, false, "Delivery failed: " + describe(e));
        }

        if (result.success) {
            delivered++;
            state = State.IDLE;
            lastMessage = result.message;
            notify(job, true, result.message);
            return;
        }

        if (result.retryable && job.attempt < MAX_ATTEMPTS) {
            retries++;
            state = State.RETRYING;
            lastMessage = "Retrying delivery (" + job.attempt + "/" + MAX_ATTEMPTS + "): " + result.message;
            Job next = new Job(job.url, job.payload, job.listener, job.attempt + 1);
            synchronized (this) {
                queue.addFirst(next);
            }
            sleep(result.delayMs > 0 ? result.delayMs : 250L * job.attempt);
            return;
        }

        failed++;
        state = State.FAILED;
        lastMessage = result.message;
        notify(job, false, result.message);
    }

    private Result attempt(String url, String payload) throws IOException, InterruptedException {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return new Result(false, false, "Webhook URL must start with http:// or https://.");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "LogClient");

            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) return new Result(true, false, "Delivered (HTTP " + code + ").");
            if (code == 429) return new Result(false, true, "Rate limited (HTTP 429).", retryDelay(connection));
            if (code >= 500) return new Result(false, true, "Server error (HTTP " + code + ").");
            return new Result(false, false, "Webhook rejected the payload (HTTP " + code + ").");
        } finally {
            if (connection != null) connection.disconnect();
        }
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.min(millis, MAX_RETRY_DELAY_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void notify(Job job, boolean success, String message) {
        if (job.listener != null) job.listener.onResult(success, message);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** Waits until the queue is empty; used by tests. */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (this) {
                if (queue.isEmpty() && !running) return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private record Job(String url, String payload, Listener listener, int attempt) {
    }

    private record Result(boolean success, boolean retryable, String message, long delayMs) {
        Result(boolean success, boolean retryable, String message) {
            this(success, retryable, message, 0);
        }
    }
}
