package com.logvex.addon.framework.orbit;

import com.logvex.addon.LogAddon;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small synchronous event bus.
 *
 * <p>Listeners are plain objects with {@link EventHandler} methods. The bus keeps one listener list
 * per concrete event type, so dispatch walks only the listeners that can receive the event. All
 * dispatch happens on the thread that posts the event — the client thread — so listeners may touch
 * Minecraft state directly.
 *
 * <p>Registering the same listener object twice is a no-op, which keeps module re-initialisation
 * from accumulating duplicate subscriptions.
 */
public final class EventBus {
    /** A cached {@code listener -> handlers} lookup, one entry per registered object. */
    private static final class Registration {
        final Object listener;
        final Map<Class<?>, List<Method>> handlers;

        Registration(Object listener, Map<Class<?>, List<Method>> handlers) {
            this.listener = listener;
            this.handlers = handlers;
        }
    }

    private final Map<Class<?>, List<Registration>> byEvent = new ConcurrentHashMap<>();
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    public void subscribe(Object listener) {
        if (listener == null) return;
        for (Registration existing : registrations) {
            if (existing.listener == listener) return;
        }

        Map<Class<?>, List<Method>> handlers = scan(listener.getClass());
        if (handlers.isEmpty()) return;

        Registration registration = new Registration(listener, handlers);
        registrations.add(registration);
        for (Class<?> event : handlers.keySet()) {
            byEvent.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(registration);
        }
    }

    public void unsubscribe(Object listener) {
        if (listener == null) return;
        Registration found = null;
        for (Registration existing : registrations) {
            if (existing.listener == listener) {
                found = existing;
                break;
            }
        }
        if (found == null) return;

        registrations.remove(found);
        for (Map.Entry<Class<?>, List<Registration>> entry : byEvent.entrySet()) {
            entry.getValue().remove(found);
        }
    }

    /** Delivers {@code event} to every listener registered for its concrete type. */
    public void post(Object event) {
        if (event == null) return;
        List<Registration> listeners = byEvent.get(event.getClass());
        if (listeners == null || listeners.isEmpty()) return;

        for (Registration registration : listeners) {
            for (Method handler : registration.handlers.get(event.getClass())) {
                try {
                    handler.invoke(registration.listener, event);
                } catch (IllegalAccessException e) {
                    LogAddon.LOG.error("Event handler not accessible: {}", handler, e);
                } catch (InvocationTargetException e) {
                    LogAddon.LOG.error("Event handler threw for {}", event.getClass().getSimpleName(),
                        e.getCause());
                }
            }
        }
    }

    /** True when {@code listener} is registered. */
    public boolean isSubscribed(Object listener) {
        for (Registration registration : registrations) {
            if (registration.listener == listener) return true;
        }
        return false;
    }

    private static Map<Class<?>, List<Method>> scan(Class<?> type) {
        Map<Class<?>, List<Method>> handlers = new ConcurrentHashMap<>();
        List<Method> found = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 1) continue;
                if (!method.isAnnotationPresent(EventHandler.class)) continue;
                method.setAccessible(true);
                found.add(method);
            }
        }
        found.sort(Comparator.comparingInt(m -> -m.getAnnotation(EventHandler.class).priority()));
        for (Method method : found) {
            Class<?> event = method.getParameterTypes()[0];
            handlers.computeIfAbsent(event, k -> new ArrayList<>()).add(method);
        }
        return handlers;
    }
}
