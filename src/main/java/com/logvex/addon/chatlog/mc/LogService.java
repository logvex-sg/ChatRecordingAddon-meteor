package com.logvex.addon.chatlog.mc;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DuplicateDetector;
import com.logvex.addon.chatlog.core.KeywordMatcher;
import com.logvex.addon.chatlog.core.KeywordRule;
import com.logvex.addon.chatlog.core.LogSession;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.PlayerTracker;
import com.logvex.addon.chatlog.core.StatsCollector;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.core.WebhookPayload;
import com.logvex.addon.chatlog.core.WebhookQueue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single coordinator shared by every chat-log module, HUD element and GUI screen.
 *
 * <p>It owns the storage service, the live buffer, the session, the statistics, the player tracker
 * and the webhook queue. Recording is cheap: an incoming entry is classified by the caller, added
 * to the in-memory structures, offered to the live listeners and handed to the storage worker. No
 * blocking work happens on the client thread.
 */
public final class LogService {
    /** How many recent entries the live HUD keeps in memory. */
    public static final int LIVE_BUFFER_SIZE = 200;

    private static final LogService INSTANCE = new LogService();

    public static LogService get() {
        return INSTANCE;
    }

    /** A listener notified on the client thread whenever a new entry is recorded. */
    public interface LiveListener {
        void onEntry(ChatEntry entry);
    }

    private final Deque<ChatEntry> live = new ArrayDeque<>();
    private final List<LiveListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    private final PlayerTracker players = new PlayerTracker();
    private final StatsCollector stats = new StatsCollector();
    private final WebhookQueue webhook = new WebhookQueue();

    private volatile LogStorageService storage;
    private volatile LogSession session;
    private volatile boolean recording;
    private volatile Set<MessageType> recordedTypes = EnumSet.allOf(MessageType.class);
    private volatile DuplicateDetector duplicates = new DuplicateDetector(0);
    private volatile List<KeywordRule> keywordRules = List.of();
    private volatile String serverName = "unknown";
    private volatile String lastError = "";

    private LogService() {
    }

    // ------------------------------------------------------------------ lifecycle

    public void attach(LogStorageService storage) {
        this.storage = storage;
    }

    public LogStorageService storage() {
        return storage;
    }

    public WebhookQueue webhook() {
        return webhook;
    }

    public PlayerTracker players() {
        return players;
    }

    public StatsCollector stats() {
        return stats;
    }

    public void setRecording(boolean value) {
        this.recording = value;
    }

    public boolean isRecording() {
        return recording;
    }

    public void setRecordedTypes(Set<MessageType> types) {
        this.recordedTypes = types == null || types.isEmpty()
            ? EnumSet.noneOf(MessageType.class)
            : EnumSet.copyOf(types);
    }

    public Set<MessageType> recordedTypes() {
        return recordedTypes;
    }

    public void setDuplicateWindow(long windowMs) {
        if (duplicates.windowMs() != windowMs) duplicates = new DuplicateDetector(windowMs);
    }

    public DuplicateDetector duplicates() {
        return duplicates;
    }

    public boolean shouldRecord(MessageType type) {
        return recording && recordedTypes.contains(type);
    }

    public void setKeywordRules(List<KeywordRule> rules) {
        this.keywordRules = rules == null ? List.of() : List.copyOf(rules);
    }

    public List<KeywordRule> keywordRules() {
        return keywordRules;
    }

    public void setServerName(String server) {
        this.serverName = server == null || server.isBlank() ? "unknown" : server;
    }

    public String serverName() {
        return serverName;
    }

    public String lastError() {
        return lastError;
    }

    public void reportError(String message) {
        this.lastError = message == null ? "" : message;
    }

    public LogSession session() {
        return session;
    }

    /** Opens a new session for {@code server}, closing any previous one. */
    public void startSession(String server, long nowMs) {
        if (session != null && session.endedAt() == 0) session = session.withEnded(nowMs);
        this.serverName = server == null || server.isBlank() ? "unknown" : server;
        String id = "s" + nowMs + "-" + sequence.incrementAndGet();
        this.session = new LogSession(id, this.serverName, nowMs, 0);
        stats.start(nowMs);
    }

    public void endSession(long nowMs) {
        if (session != null && session.endedAt() == 0) session = session.withEnded(nowMs);
        stats.stop(nowMs);
        flush(null);
    }

    public void flush(Runnable onDone) {
        LogStorageService current = storage;
        if (current == null) {
            if (onDone != null) onDone.run();
            return;
        }
        current.flushAsync(onDone);
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records an entry. Returns true when it was accepted. The caller is responsible for having
     * already filtered the entry through {@link #shouldRecord(MessageType)} if it wants the
     * category settings honoured; this method re-checks to be safe.
     */
    public boolean record(ChatEntry entry) {
        if (entry == null || !shouldRecord(entry.type())) return false;

        ChatEntry stamped = entry;
        if (entry.sessionId().isBlank() && session != null) {
            stamped = new ChatEntry(entry.timestamp(), entry.type(), entry.sender(), entry.content(),
                serverName, session.id());
        } else if (entry.server().isBlank()) {
            stamped = new ChatEntry(entry.timestamp(), entry.type(), entry.sender(), entry.content(),
                serverName, entry.sessionId());
        }

        if (!duplicates.accept(stamped)) return false;
        synchronized (live) {
            live.addLast(stamped);
            while (live.size() > LIVE_BUFFER_SIZE) live.removeFirst();
        }

        stats.record(stamped);
        players.record(stamped);

        LogStorageService current = storage;
        if (current != null) current.appendAsync(stamped);

        for (LiveListener listener : listeners) {
            try {
                listener.onEntry(stamped);
            } catch (RuntimeException e) {
                // A misbehaving listener must not break recording.
                lastError = "Live listener failed: " + e.getMessage();
            }
        }
        return true;
    }

    /** Recent entries, newest first. */
    public List<ChatEntry> recent(int limit) {
        List<ChatEntry> snapshot;
        synchronized (live) {
            snapshot = new ArrayList<>(live);
        }
        List<ChatEntry> out = new ArrayList<>(Math.min(limit, snapshot.size()));
        for (int i = snapshot.size() - 1; i >= 0 && out.size() < limit; i--) out.add(snapshot.get(i));
        return out;
    }

    public int liveCount() {
        synchronized (live) {
            return live.size();
        }
    }

    public void addListener(LiveListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(LiveListener listener) {
        listeners.remove(listener);
    }

    public StatsSnapshot snapshot() {
        return stats.snapshot(System.currentTimeMillis());
    }

    /** Sends a keyword alert to the webhook when the rule asks for it. */
    public boolean notifyKeyword(String url, String username, KeywordRule rule, ChatEntry entry) {
        if (url == null || url.isBlank()) return false;
        String payload = WebhookPayload.alert(username, rule, entry);
        return webhook.enqueue(url, payload, null);
    }

    /** Sends a periodic summary of the given entries. */
    public boolean notifySummary(String url, String username, List<ChatEntry> entries) {
        return notifySummary(url, username, entries, null);
    }

    /** Sends a summary and reports the delivery result back to {@code listener}. */
    public boolean notifySummary(String url, String username, List<ChatEntry> entries, WebhookQueue.Listener listener) {
        if (url == null || url.isBlank() || entries == null || entries.isEmpty()) return false;
        String payload = WebhookPayload.summary(username, serverName, entries);
        return webhook.enqueue(url, payload, listener);
    }

    /** Fires a one-off test delivery. */
    public void testWebhook(String url, String username, WebhookQueue.Listener listener) {
        webhook.enqueue(url, WebhookPayload.test(username), listener);
    }

    /** True when any enabled rule requests a webhook notification for this entry. */
    public boolean wantsWebhook(ChatEntry entry) {
        return KeywordMatcher.anyWebhook(keywordRules, entry);
    }
}
