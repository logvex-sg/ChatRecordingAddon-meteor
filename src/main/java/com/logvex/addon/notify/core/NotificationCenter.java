package com.logvex.addon.notify.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single place every Log Client feature reports notable events.
 *
 * <p>The centre is Minecraft-free and thread-safe: producers may be on the client thread, a disk
 * worker or a network callback, so every mutation is guarded and listeners are invoked outside the
 * lock. History is a bounded ring so a misbehaving producer can never grow memory without limit,
 * and two rate limiters keep a repeated event from flooding chat or the HUD.
 */
public final class NotificationCenter {
    /** Hard cap on retained history; older entries are dropped, never grown past this. */
    public static final int HISTORY_LIMIT = 250;

    /** How many notifications may be accepted across all sources within one window. */
    private static final int BURST_LIMIT = 25;
    private static final long BURST_WINDOW_MS = 1_000;

    /** Identical (source, type, title) notifications are suppressed for this long. */
    private static final long DEDUPE_MS = 1_500;

    /** A receiver of accepted notifications. */
    public interface Listener {
        void onNotification(Notification notification);
    }

    private final Deque<Notification> history = new ArrayDeque<>(HISTORY_LIMIT);
    private final List<Listener> listeners = new ArrayList<>();

    // Rate limiting state.
    private final Deque<Long> burstWindow = new ArrayDeque<>();
    private final Map<String, Long> dedupe = new HashMap<>();

    // Filters.
    private volatile NotificationPriority minPriority = NotificationPriority.LOW;
    private volatile Set<NotificationType> enabledTypes = EnumSet.allOf(NotificationType.class);
    private final Set<String> mutedSources = new java.util.HashSet<>();

    private long accepted;
    private long suppressed;

    /**
     * Accepts a notification if it passes the filters and rate limiters.
     *
     * @return true when it was stored and delivered; false when filtered or suppressed.
     */
    public boolean publish(Notification notification) {
        if (notification == null) return false;

        List<Listener> toNotify;
        synchronized (this) {
            if (!passesFilters(notification)) {
                suppressed++;
                return false;
            }
            long now = notification.timestamp() > 0 ? notification.timestamp() : System.currentTimeMillis();
            if (rateLimited(notification, now)) {
                suppressed++;
                return false;
            }

            history.addLast(notification);
            while (history.size() > HISTORY_LIMIT) history.removeFirst();
            accepted++;

            if (listeners.isEmpty()) return true;
            toNotify = new ArrayList<>(listeners);
        }
        for (Listener listener : toNotify) {
            try {
                listener.onNotification(notification);
            } catch (RuntimeException ignored) {
                // A broken listener must never break the producer or the other listeners.
            }
        }
        return true;
    }

    private boolean passesFilters(Notification notification) {
        if (!notification.priority().atLeast(minPriority)) return false;
        if (!enabledTypes.contains(notification.type())) return false;
        String source = notification.source().toLowerCase(Locale.ROOT);
        return !mutedSources.contains(source);
    }

    /** Caller holds the lock. */
    private boolean rateLimited(Notification notification, long now) {
        while (!burstWindow.isEmpty() && now - burstWindow.peekFirst() > BURST_WINDOW_MS) {
            burstWindow.removeFirst();
        }
        if (burstWindow.size() >= BURST_LIMIT) return true;

        String key = (notification.source() + '\u0000' + notification.type()
            + '\u0000' + notification.title()).toLowerCase(Locale.ROOT);
        Long last = dedupe.get(key);
        if (last != null && now - last < DEDUPE_MS) return true;

        burstWindow.addLast(now);
        dedupe.put(key, now);
        if (dedupe.size() > 512) pruneDedupe(now);
        return false;
    }

    /** Caller holds the lock. */
    private void pruneDedupe(long now) {
        dedupe.entrySet().removeIf(entry -> now - entry.getValue() > DEDUPE_MS * 8);
    }

    /** The most recent notifications, newest first. */
    public synchronized List<Notification> recent(int limit) {
        int count = Math.max(0, Math.min(limit, history.size()));
        List<Notification> out = new ArrayList<>(count);
        var iterator = history.descendingIterator();
        while (iterator.hasNext() && out.size() < count) out.add(iterator.next());
        return out;
    }

    /** All retained history, newest first. */
    public synchronized List<Notification> history() {
        return recent(history.size());
    }

    /** Notifications at or above {@code priority}, newest first. */
    public synchronized List<Notification> recent(int limit, NotificationPriority priority) {
        List<Notification> out = new ArrayList<>();
        var iterator = history.descendingIterator();
        while (iterator.hasNext() && out.size() < limit) {
            Notification n = iterator.next();
            if (n.priority().atLeast(priority)) out.add(n);
        }
        return out;
    }

    /** Case-insensitive search across the retained history, newest first. */
    public synchronized List<Notification> search(String keyword, int limit) {
        List<Notification> out = new ArrayList<>();
        var iterator = history.descendingIterator();
        while (iterator.hasNext() && out.size() < limit) {
            Notification n = iterator.next();
            if (n.matches(keyword)) out.add(n);
        }
        return out;
    }

    public synchronized int size() {
        return history.size();
    }

    public synchronized boolean isEmpty() {
        return history.isEmpty();
    }

    public synchronized long accepted() {
        return accepted;
    }

    public synchronized long suppressed() {
        return suppressed;
    }

    public synchronized void clear() {
        history.clear();
    }

    // ---------------------------------------------------------------- listeners

    public synchronized void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---------------------------------------------------------------- filters

    public void setMinPriority(NotificationPriority priority) {
        if (priority != null) this.minPriority = priority;
    }

    public NotificationPriority minPriority() {
        return minPriority;
    }

    public void setEnabledTypes(Set<NotificationType> types) {
        this.enabledTypes = types == null || types.isEmpty()
            ? EnumSet.noneOf(NotificationType.class)
            : EnumSet.copyOf(types);
    }

    public Set<NotificationType> enabledTypes() {
        return EnumSet.copyOf(enabledTypes);
    }

    public synchronized void muteSource(String source) {
        if (source != null) mutedSources.add(source.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized void unmuteSource(String source) {
        if (source != null) mutedSources.remove(source.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized boolean isMuted(String source) {
        return source != null && mutedSources.contains(source.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized Set<String> mutedSources() {
        return Set.copyOf(mutedSources);
    }
}
