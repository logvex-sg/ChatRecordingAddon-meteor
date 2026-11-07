package com.logvex.addon.companion.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small, bounded publish/subscribe hub for the companion app.
 *
 * <p>Every event is stored in a ring of the most recent {@code capacity} events and pushed to the
 * subscribers that are connected right now. A subscriber that is added later receives the backlog
 * first, so an app that reconnects after a blip does not miss what happened while it was away, and a
 * subscriber that is too slow is dropped rather than being allowed to stall the publisher.
 *
 * <p>Publishing happens on whichever thread produced the event (usually the client tick). Subscriber
 * delivery must therefore be non-blocking, which is what the SSE connection guarantees by writing to
 * a queue rather than to the socket.
 */
public final class CompanionEventBus {
    /** One event: a monotonically increasing id, a type the app switches on, and a JSON payload. */
    public record Event(long id, String type, String json, long timestamp) {
    }

    /** A subscriber is told about each event and asked to stop when it cannot keep up. */
    public interface Subscriber {
        /**
         * Receives one event.
         *
         * @return false to be unsubscribed, for example when the connection has died
         */
        boolean onEvent(Event event);
    }

    private final int capacity;
    private final Deque<Event> history = new ArrayDeque<>();
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public CompanionEventBus(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Publishes an event and returns it. The payload is expected to be already-serialised JSON. */
    public Event publish(String type, String json) {
        Event event = new Event(sequence.incrementAndGet(), type, json == null ? "null" : json,
            System.currentTimeMillis());
        synchronized (history) {
            history.addLast(event);
            while (history.size() > capacity) history.removeFirst();
        }
        for (Subscriber subscriber : subscribers) {
            boolean keep;
            try {
                keep = subscriber.onEvent(event);
            } catch (RuntimeException e) {
                // A broken subscriber must never break the publisher or the other subscribers.
                keep = false;
            }
            if (!keep) subscribers.remove(subscriber);
        }
        return event;
    }

    /** Registers a subscriber and replays the backlog to it. Returns the subscriber for removal. */
    public Subscriber subscribe(Subscriber subscriber, boolean replayHistory) {
        if (replayHistory) {
            List<Event> backlog;
            synchronized (history) {
                backlog = new ArrayList<>(history);
            }
            for (Event event : backlog) {
                boolean keep;
                try {
                    keep = subscriber.onEvent(event);
                } catch (RuntimeException e) {
                    keep = false;
                }
                if (!keep) return subscriber;
            }
        }
        subscribers.add(subscriber);
        return subscriber;
    }

    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** The most recent events, newest last, copied so the caller cannot mutate the ring. */
    public List<Event> recent(int limit) {
        synchronized (history) {
            int from = Math.max(0, history.size() - Math.max(0, limit));
            List<Event> out = new ArrayList<>();
            int index = 0;
            for (Event event : history) {
                if (index++ >= from) out.add(event);
            }
            return out;
        }
    }

    public long lastEventId() {
        return sequence.get();
    }
}
