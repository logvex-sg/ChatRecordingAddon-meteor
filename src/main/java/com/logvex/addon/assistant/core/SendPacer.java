package com.logvex.addon.assistant.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Paces outgoing chat lines so a multi-line reply cannot be mistaken for a flood.
 *
 * <p>A block such as {@code !cmds} renders to a dozen lines. Sending them all in one tick is what a
 * spam filter sees as a flood, and servers respond to that with a kick. The lines are still worth
 * sending — the block only reads as a table when it arrives in order — so they are queued and
 * released a few at a time instead of being dropped or flattened into one long line.
 *
 * <p>This class holds no clock and sends nothing: the caller supplies {@code now} and asks what is
 * ready. That keeps the pacing rules — which are the part worth getting right — testable without a
 * game, and it means the module can drive the queue from its tick handler.
 *
 * <p>Two limits, because servers enforce both:
 * <ul>
 *   <li>{@code minGapMs} between consecutive lines, so no two lines leave in the same tick;</li>
 *   <li>{@code maxPerBurst} lines per {@code burstWindowMs}, so a long block still cannot empty
 *       itself faster than a human could type.</li>
 * </ul>
 *
 * <p>A queue that grows without bound is its own problem: if the player asks for five help pages in
 * a row the backlog would trail behind them for minutes. Past {@code capacity} the oldest pending
 * line is dropped and {@link #dropped()} counts it, so the caller can say so rather than silently
 * losing half a table.
 */
public final class SendPacer {
    /** A single line waiting to be sent, with what is needed to send it. */
    public record Line(String recipient, String verb, String text) {
        public Line {
            if (recipient == null) recipient = "";
            if (verb == null || verb.isBlank()) verb = TriggerRule.DEFAULT_WHISPER;
            if (text == null) text = "";
        }

        /** True when this line is addressed to one player rather than to public chat. */
        public boolean isWhisper() {
            return !recipient.isBlank();
        }
    }

    private final long minGapMs;
    private final int maxPerBurst;
    private final long burstWindowMs;
    private final int capacity;

    private final Deque<Line> pending = new ArrayDeque<>();
    private final Deque<Long> recentSends = new ArrayDeque<>();
    private long lastSendAt = Long.MIN_VALUE;
    private int dropped;

    public SendPacer(long minGapMs, int maxPerBurst, long burstWindowMs, int capacity) {
        this.minGapMs = Math.max(0, minGapMs);
        this.maxPerBurst = Math.max(1, maxPerBurst);
        this.burstWindowMs = Math.max(0, burstWindowMs);
        this.capacity = Math.max(1, capacity);
    }

    /** The default pacing: one line per second, at most three per five seconds, 40 lines deep. */
    public static SendPacer defaults() {
        return new SendPacer(1_000L, 3, 5_000L, 40);
    }

    /** A pacer that never delays anything, for a caller that has already decided to send at once. */
    public static SendPacer immediate() {
        return new SendPacer(0L, Integer.MAX_VALUE, 0L, Integer.MAX_VALUE);
    }

    /** Queues {@code lines}; returns how many were accepted. */
    public int enqueue(List<Line> lines) {
        if (lines == null || lines.isEmpty()) return 0;
        int accepted = 0;
        for (Line line : lines) {
            if (line == null || line.text().isBlank()) continue;
            while (pending.size() >= capacity) {
                pending.removeFirst();
                dropped++;
            }
            pending.addLast(line);
            accepted++;
        }
        return accepted;
    }

    /** Queues one line; returns false when it was blank and so never queued. */
    public boolean enqueue(Line line) {
        return enqueue(List.of(line)) == 1;
    }

    /**
     * Removes and returns the lines that may be sent at {@code now}.
     *
     * <p>Returns at most one line per call unless the gap is zero: a burst limit is only meaningful
     * if the caller releases lines one at a time, and releasing one per tick is also what keeps the
     * lines in order on the server's side.
     */
    public List<Line> ready(long now) {
        if (pending.isEmpty()) return List.of();
        if (minGapMs > 0 && lastSendAt != Long.MIN_VALUE && now - lastSendAt < minGapMs) {
            return List.of();
        }
        pruneBurst(now);
        if (recentSends.size() >= maxPerBurst) return List.of();

        Line line = pending.removeFirst();
        lastSendAt = now;
        recentSends.addLast(now);
        return List.of(line);
    }

    /** How long until {@link #ready} could return something, in milliseconds; 0 when it could now. */
    public long waitMs(long now) {
        if (pending.isEmpty()) return 0;
        long gapWait = 0;
        if (minGapMs > 0 && lastSendAt != Long.MIN_VALUE) {
            gapWait = Math.max(0, minGapMs - (now - lastSendAt));
        }
        pruneBurst(now);
        long burstWait = 0;
        if (recentSends.size() >= maxPerBurst) {
            Long oldest = recentSends.peekFirst();
            if (oldest != null) burstWait = Math.max(0, burstWindowMs - (now - oldest));
        }
        return Math.max(gapWait, burstWait);
    }

    private void pruneBurst(long now) {
        while (!recentSends.isEmpty() && now - recentSends.peekFirst() >= burstWindowMs) {
            recentSends.removeFirst();
        }
    }

    /** True when nothing is waiting. */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** How many lines are waiting. */
    public int pendingCount() {
        return pending.size();
    }

    /** How many lines were discarded because the queue was full. */
    public int dropped() {
        return dropped;
    }

    /** Empties the queue, for a world change where the backlog would arrive out of context. */
    public List<Line> clear() {
        List<Line> out = new ArrayList<>(pending);
        pending.clear();
        recentSends.clear();
        lastSendAt = Long.MIN_VALUE;
        return List.copyOf(out);
    }
}