package com.logvex.addon.automation;

import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.EventPriority;
import meteordevelopment.orbit.ICancellable;
import meteordevelopment.orbit.listeners.IListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the dispatch order the auto-login fix depends on, against the real orbit bus.
 *
 * <p>The bug is subtle enough that reading the source is not good enough. {@code EventBus.post}
 * stops calling listeners as soon as one cancels, so a listener that cancels earlier can hide the
 * event from every listener behind it. The chat filter cancels {@code ReceiveMessageEvent} so the
 * message never reaches the HUD, and the auto-login module has to see the same line anyway or a
 * suppressed login prompt silently leaves the player logged out.
 *
 * <p>Raising the module to {@code HIGHEST} would <em>not</em> have been enough: the bus breaks a
 * priority tie by subscription order, and the filter subscribes at bootstrap before any module can
 * be enabled. These tests pin that down so a future edit cannot quietly reintroduce the bug.
 *
 * <p>Uses {@link IListener} rather than annotated methods because the annotated path needs orbit's
 * lambda factory, which only Meteor installs at client startup. The listener interface is what the
 * bus actually sorts and calls, so the ordering proved here is the real one.
 */
class ReceiveMessageDispatchOrderTest {

    /** Minimal cancellable event; the real one needs a Minecraft text object. */
    private static final class Message implements ICancellable {
        private boolean cancelled;

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Records the order listeners ran in, and can cancel the event. */
    private static final class Recorder implements IListener {
        private final List<String> order;
        private final String name;
        private final int priority;
        private final boolean cancels;

        Recorder(List<String> order, String name, int priority, boolean cancels) {
            this.order = order;
            this.name = name;
            this.priority = priority;
            this.cancels = cancels;
        }

        @Override
        public void call(Object event) {
            order.add(name);
            if (cancels && event instanceof ICancellable cancellable) cancellable.setCancelled(true);
        }

        @Override
        public Class<?> getTarget() {
            return Message.class;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        @Deprecated
        public boolean isStatic() {
            return false;
        }
    }

    private static EventBus busWith(Recorder... listeners) {
        EventBus bus = new EventBus();
        for (Recorder listener : listeners) bus.subscribe(listener);
        return bus;
    }

    @Test
    @DisplayName("a cancelling listener hides the event from everything behind it")
    void aCancellingListenerStopsTheDispatch() {
        List<String> order = new ArrayList<>();
        EventBus bus = busWith(
            new Recorder(order, "filter", EventPriority.HIGHEST, true),
            new Recorder(order, "autologin", EventPriority.MEDIUM, false));

        bus.post(new Message());

        assertEquals(List.of("filter"), order,
            "the bus must stop at the canceller, which is what made the bug possible");
    }

    @Test
    @DisplayName("matching the filter's priority is not enough, because the tie goes to subscription order")
    void equalPriorityLosesToTheEarlierSubscriber() {
        List<String> order = new ArrayList<>();
        // The filter subscribes first, exactly as it does at addon bootstrap.
        EventBus bus = busWith(
            new Recorder(order, "filter", EventPriority.HIGHEST, true),
            new Recorder(order, "autologin", EventPriority.HIGHEST, false));

        bus.post(new Message());

        assertEquals(List.of("filter"), order,
            "at equal priority the later subscriber runs second and never sees the cancelled event");
    }

    @Test
    @DisplayName("a priority above the filter's wins, so auto-login sees the prompt")
    void aHigherPriorityRunsFirstAndSeesThePrompt() {
        List<String> order = new ArrayList<>();
        EventBus bus = busWith(
            new Recorder(order, "filter", EventPriority.HIGHEST, true),
            new Recorder(order, "autologin", EventPriority.HIGHEST + 1, false));

        bus.post(new Message());

        assertEquals(List.of("autologin", "filter"), order,
            "the module must read the line before the filter cancels it");
    }

    @Test
    @DisplayName("the module still does not stop the message being hidden")
    void theModuleDoesNotKeepAHiddenMessageVisible() {
        List<String> order = new ArrayList<>();
        EventBus bus = busWith(
            new Recorder(order, "filter", EventPriority.HIGHEST, true),
            new Recorder(order, "autologin", EventPriority.HIGHEST + 1, false));

        Message event = new Message();
        bus.post(event);

        assertTrue(event.isCancelled(),
            "reading the prompt first must not rescue a message the user asked to hide");
    }
}
