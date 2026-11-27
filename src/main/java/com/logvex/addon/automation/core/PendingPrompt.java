package com.logvex.addon.automation.core;

/**
 * Holds a login prompt until it can actually be answered.
 *
 * <p>A prompt is a one-shot event: the server says "please /login" once and then waits. The module
 * used to consume the prompt on the next tick whether or not it could act on it, so a prompt that
 * arrived a moment too early, or during the send cooldown, or before a password had been typed in,
 * was thrown away and the player was never logged in. That is the reported "auto-login does nothing"
 * failure: the prompt was seen and then dropped.
 *
 * <p>Keeping the prompt has to be bounded, or a stale prompt could fire minutes later and send a
 * password into a session the server has already forgotten about. The value therefore expires after
 * a short window, which is long enough to cover a cooldown or the time it takes to type a password
 * and short enough that a leftover prompt is never sent into an unrelated part of the session.
 *
 * <p>Minecraft-free and clock-injected so the retention rule is unit tested.
 */
public final class PendingPrompt {
    /**
     * How long a prompt stays answerable.
     *
     * <p>Chosen to outlast the longest useful wait (a cooldown plus the moment it takes to open the
     * GUI and type a password) while staying far below the point where the server would have
     * kicked an unauthenticated player.
     */
    public static final long DEFAULT_TTL_MS = 30_000;

    private final long ttlMs;
    private String text;
    private long receivedAt;

    public PendingPrompt() {
        this(DEFAULT_TTL_MS);
    }

    public PendingPrompt(long ttlMs) {
        this.ttlMs = Math.max(1, ttlMs);
    }

    /** Records a prompt, replacing any earlier one: only the newest prompt is worth answering. */
    public void offer(String prompt, long now) {
        if (prompt == null || prompt.isBlank()) return;
        text = prompt;
        receivedAt = now;
    }

    /**
     * The prompt still waiting to be answered, or null when there is none or it has expired.
     *
     * <p>Reading does not consume: the caller peeks, decides whether it can send, and only clears
     * once the prompt has been acted on or given up on. That split is the whole fix — the old code
     * cleared first and decided afterwards.
     */
    public String peek(long now) {
        if (text == null) return null;
        if (now - receivedAt > ttlMs) {
            clear();
            return null;
        }
        return text;
    }

    /** True when a prompt is waiting and has not expired. */
    public boolean waiting(long now) {
        return peek(now) != null;
    }

    /** Discards the prompt; call once it has been answered or is known to be unanswerable. */
    public void clear() {
        text = null;
        receivedAt = 0;
    }

    /** The TTL this instance uses, for status lines and tests. */
    public long ttlMs() {
        return ttlMs;
    }
}
