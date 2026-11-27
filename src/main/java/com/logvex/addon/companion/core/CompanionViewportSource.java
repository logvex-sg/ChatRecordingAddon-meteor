package com.logvex.addon.companion.core;

/**
 * A source of live pictures of the game window.
 *
 * <p>This is the seam between the HTTP surface and the render thread. The router never captures
 * anything itself: it asks a source for one frame and the Minecraft layer decides how to produce it.
 * Keeping it an interface means the frame protocol, the quality negotiation and the control rules
 * can be tested without a renderer.
 *
 * <p>A frame is a complete image, not a delta. Streaming full frames is what makes a viewer correct
 * after a reconnect, a dimension change or a dropped packet: the next frame it receives is the
 * current world, not a patch against a world it no longer has.
 */
public interface CompanionViewportSource {

    /** One encoded frame and what it is worth knowing about it. */
    record Frame(byte[] png, int width, int height, long capturedAtEpochMs) {
    }

    /**
     * Captures the current game window.
     *
     * <p>Called on the client thread, so it must not block: the caller has already arranged for it to
     * run between frames. Answers null when there is nothing to show, which is a real state — a
     * loading screen, a minimised window, or a client that is not in a world.
     *
     * @param targetWidth  the width the caller would like, or 0 for the window's own size
     * @param quality      1-100, where lower is a smaller PNG
     */
    Frame capture(int targetWidth, int quality);

    /** The largest frame the viewer may ask for, so a request cannot force a huge allocation. */
    int maxWidth();

    /** The smallest frame that is still useful, so a viewer cannot ask for a 1-pixel stream. */
    int minWidth();

    /** Whether the client can produce frames at all right now. */
    boolean available();
}
