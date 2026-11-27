package com.logvex.addon.companion.core;

import java.util.List;

/**
 * Remote control of the running client: the "drive it from the browser" half of PrismView.
 *
 * <p>Every method is a deliberate, narrow action rather than a way to run arbitrary input. There is
 * no "send this key code" method, because that would let a viewer drive menus, close the game or type
 * into any screen the client happens to have open. What is offered is what a viewer legitimately
 * needs to walk around and interact with the world: movement, looking, two mouse buttons, and the
 * hotbar.
 *
 * <p>Movement is expressed as a held set rather than key presses. A browser sends "forward is down",
 * then later "forward is up", and the client applies the difference to the game's own key bindings.
 * That survives a dropped connection: {@link #releaseAll()} is called when a viewer goes away, so a
 * client never keeps walking because a tab was closed.
 */
public interface CompanionControlTarget {

    /**
     * Holds or releases the movement inputs.
     *
     * <p>The names are the game's own: {@code forward}, {@code back}, {@code left}, {@code right},
     * {@code jump}, {@code sneak} and {@code sprint}. An unknown name is refused rather than ignored,
     * so a viewer is told its request was wrong instead of silently not moving.
     *
     * @return null on success, or a plain sentence explaining the refusal
     */
    String setInputs(List<String> held);

    /**
     * Looks by a relative amount, in degrees.
     *
     * <p>Relative rather than absolute because a browser only knows how far the pointer moved; an
     * absolute angle would fight the player's own mouse when both are used.
     *
     * @param deltaYaw   degrees to turn right (negative turns left)
     * @param deltaPitch degrees to look down (negative looks up)
     */
    String look(double deltaYaw, double deltaPitch);

    /**
     * Presses a mouse button against the world.
     *
     * @param button 0 for attack/break, 1 for use/place, 2 for pick block
     * @param hold   true to hold it down, false to release it
     */
    String mouse(int button, boolean hold);

    /** Selects a hotbar slot, 0-8. */
    String selectHotbar(int slot);

    /**
     * Releases every input and button this target is holding.
     *
     * <p>Called when a viewer disconnects and when remote control is switched off. It must be safe to
     * call at any time, including when nothing is held.
     */
    void releaseAll();

    /** Whether remote control is switched on at all, from the client's own configuration. */
    boolean enabled();

    /** The input names {@link #setInputs(List)} accepts, so the app can offer them. */
    static List<String> inputNames() {
        return List.of("forward", "back", "left", "right", "jump", "sneak", "sprint");
    }
}
