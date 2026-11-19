package com.logvex.addon.automation.core;

/**
 * Decides when to send a login command, and when to stop.
 *
 * <p>The client must not send the password blindly on join: on a server with no login plugin the
 * {@code /login} command either errors or, worse, is sent as ordinary chat and read by everyone.
 * So an attempt only happens in response to a prompt the {@link LoginPromptDetector} recognised.
 *
 * <p>It must also stop. A server that rejects the password gets a bounded number of tries and then
 * nothing, because a client that keeps retrying looks exactly like a brute-force attempt and gets
 * the account banned. Success stops it immediately; a reported failure stops it early.
 *
 * <p>Minecraft-free and clock-injected so the whole schedule is unit tested.
 */
public final class LoginAttemptPolicy {
    /** How many prompts one session may answer before giving up. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** Minimum gap between two sends, so a prompt repeating every tick cannot flood the server. */
    public static final long DEFAULT_COOLDOWN_MS = 4_000;

    private final int maxAttempts;
    private final long cooldownMs;

    private int attempts;
    private long lastAttemptAt;
    private boolean succeeded;
    private boolean failed;
    private boolean promptSeen;

    public LoginAttemptPolicy() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_COOLDOWN_MS);
    }

    public LoginAttemptPolicy(int maxAttempts, long cooldownMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.cooldownMs = Math.max(0, cooldownMs);
    }

    /** Resets for a new connection. Called on join and on leave. */
    public synchronized void reset() {
        attempts = 0;
        lastAttemptAt = 0;
        succeeded = false;
        failed = false;
        promptSeen = false;
    }

    /** Records that a prompt was seen, so status can distinguish "no prompt" from "gave up". */
    public synchronized void notePrompt() {
        promptSeen = true;
    }

    /**
     * Whether the password should be sent now.
     *
     * @param now the current time in milliseconds
     * @return true when an attempt is due
     */
    public synchronized boolean shouldSend(long now) {
        if (succeeded || failed) return false;
        if (attempts >= maxAttempts) return false;
        if (attempts > 0 && now - lastAttemptAt < cooldownMs) return false;
        return true;
    }

    /** Records that an attempt was sent. */
    public synchronized void markSent(long now) {
        attempts++;
        lastAttemptAt = now;
    }

    /** Records that the server accepted the password. */
    public synchronized void markSuccess() {
        succeeded = true;
    }

    /** Records that the server rejected the password; no further attempts are made. */
    public synchronized void markFailure() {
        failed = true;
    }

    public synchronized int attempts() {
        return attempts;
    }

    public synchronized int remaining() {
        return Math.max(0, maxAttempts - attempts);
    }

    public synchronized boolean succeeded() {
        return succeeded;
    }

    public synchronized boolean failed() {
        return failed;
    }

    public synchronized boolean sawPrompt() {
        return promptSeen;
    }

    /** True when attempts are used up without a success or a reported failure. */
    public synchronized boolean exhausted() {
        return !succeeded && !failed && attempts >= maxAttempts;
    }

    /** True when nothing more will be sent this session. */
    public synchronized boolean done() {
        return succeeded || failed || exhausted();
    }
}
