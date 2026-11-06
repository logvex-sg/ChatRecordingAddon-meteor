package com.logvex.addon.framework.events.world;

/**
 * Posted once per client tick.
 *
 * <p>{@link Pre} fires before the client updates the world and {@link Post} after; the addon's
 * modules listen to {@link Post}. The instances are reused because a tick event carries no state.
 */
public abstract class TickEvent {
    public static final class Pre extends TickEvent {
        private static final Pre INSTANCE = new Pre();

        public static Pre get() {
            return INSTANCE;
        }
    }

    public static final class Post extends TickEvent {
        private static final Post INSTANCE = new Post();

        public static Post get() {
            return INSTANCE;
        }
    }
}
