package com.logvex.addon.companion.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The inventory click actions the companion app may ask for.
 *
 * <p>This mirrors the five vanilla click modes the app actually needs, and nothing else. Minecraft
 * has eight {@code SlotActionType} values, but three of them are not offered here on purpose:
 * {@code QUICK_CRAFT} is the drag-paint protocol and needs a whole press/move/release sequence that a
 * single request cannot express safely, and {@code PICKUP_ALL} and the creative-only {@code CLONE}
 * are convenience gestures the app reproduces with ordinary clicks. Leaving them out means every
 * action that reaches the game has one unambiguous meaning.
 *
 * <p>The enum lives in {@code core}, not in the Minecraft package, so the router can validate a
 * request without loading a game class. The Minecraft layer maps a name onto the game's own enum at
 * the point of the click.
 */
public enum SlotAction {
    /** Left or right click: take, place, or merge the cursor stack with the slot. */
    PICKUP("pickup"),
    /** Shift-click: move the stack between the container and the inventory. */
    QUICK_MOVE("quick_move"),
    /** Number key or F: swap the slot with a hotbar slot, or with the offhand. */
    SWAP("swap"),
    /** Q or Ctrl+Q: drop from the slot, or from the cursor. */
    THROW("throw"),
    /** Creative-only middle click: copy the slot's stack onto the cursor. */
    CLONE("clone");

    private final String wire;

    SlotAction(String wire) {
        this.wire = wire;
    }

    /** The name the app sends and the API returns. */
    public String wire() {
        return wire;
    }

    /** The action with this wire name, or null when the name is not one of ours. */
    public static SlotAction fromWire(String name) {
        if (name == null) return null;
        String normalised = name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (SlotAction action : values()) {
            if (action.wire.equals(normalised)) return action;
        }
        return null;
    }

    /** Every wire name, in declaration order, for the app to build its controls from. */
    public static List<String> wireNames() {
        List<String> out = new ArrayList<>(values().length);
        for (SlotAction action : values()) out.add(action.wire);
        return out;
    }
}
