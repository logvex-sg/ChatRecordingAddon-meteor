package com.logvex.addon.companion.core;

import java.util.List;
import java.util.Map;

/**
 * The map and telemetry data the companion app reads, behind an interface.
 *
 * <p>These are read-only views over services that live in the Minecraft layer (the terrain capture
 * and the telemetry sampler). They are kept out of {@link CompanionApi} because that interface is
 * about <em>actions</em>; a map read mutates nothing. The router only calls this, so its routing and
 * error handling are tested with a fake that returns real-shaped data.
 *
 * <p>An implementation is expected to be cheap: the app polls the metadata every few seconds and
 * fetches a tile only when the viewport needs one.
 */
public interface CompanionMapSource {

    /**
     * What terrain exists: the dimensions, how many chunks each holds, the tiles that can be fetched
     * and when the area was last captured.
     *
     * <p>A dimension with no captured chunks is absent, so the app can say "no terrain yet" instead
     * of drawing an empty map that looks like flat land.
     */
    Map<String, Object> terrainMeta();

    /**
     * One compressed terrain tile, or null when nothing was captured there.
     *
     * <p>Null is meaningful: it is the difference between "unexplored" and "a tile of sea level".
     */
    byte[] terrainTile(String dimension, int tileX, int tileZ);

    /** Every waypoint in a dimension, newest first. */
    List<Map<String, Object>> waypoints(String dimension);

    /** Adds or updates a waypoint; returns the stored point, or null when the client is not in a world. */
    Map<String, Object> addWaypoint(String name, String category, Integer x, Integer y, Integer z);

    /** Removes one waypoint by its key; true when something was removed. */
    boolean removeWaypoint(String key);

    /** Every telemetry series with its samples and statistics. */
    Map<String, Object> telemetry();

    /** The telemetry as CSV, for export. */
    String telemetryCsv();
}
