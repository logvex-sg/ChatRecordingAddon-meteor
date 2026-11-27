package com.logvex.addon.companion.core;

/**
 * Item icons for the companion app, behind an interface.
 *
 * <p>The app draws an inventory, and a grid of item names is hard to read at a glance. The game
 * already has the right picture for every item, in whatever resource pack the player runs, so the
 * icon is taken from there rather than drawn or approximated here.
 *
 * <p>Kept out of {@link CompanionApi} for the same reason as {@link CompanionMapSource}: reading an
 * icon mutates nothing. The router only talks to this, so routing, caching and the 404 for an
 * unknown item are tested against a fake.
 */
public interface CompanionIconSource {

    /**
     * The PNG bytes for an item, or null when there is no icon for that id.
     *
     * <p>Null is a real answer, not a failure: an unknown item, or a client that is not in a world,
     * has no icon, and the app falls back to the item's name. Returning a placeholder image instead
     * would put a wrong picture on screen and call it the item.
     *
     * @param itemId a namespaced id such as {@code minecraft:diamond_sword}
     */
    byte[] icon(String itemId);
}
