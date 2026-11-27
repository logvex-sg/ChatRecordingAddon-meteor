package com.logvex.addon.companion.core;

/**
 * The few pieces of arithmetic the companion app and the client must agree on.
 *
 * <p>They live here, rather than inline in the Minecraft-facing code, because they are the parts
 * that can be wrong in a way nobody notices: a bearing off by 180 degrees still looks plausible on
 * a radar, and there is no way to catch that by eye. Keeping them pure means a test can pin them
 * without a running game.
 *
 * <p>Angles are degrees. "Bearing" is the compass sense used by the app: 0 is north, 90 is east,
 * 180 is south and 270 is west. That is <em>not</em> a Minecraft yaw, which is 0 at south and grows
 * clockwise; {@link #yawToBearing(float)} converts between them.
 */
public final class CompanionGeometry {

    private CompanionGeometry() {
    }

    /**
     * The compass bearing from one point to another, in degrees in {@code (-180, 180]}.
     *
     * @param dx offset east of the observer
     * @param dz offset south of the observer
     * @return 0 for due north, 90 for due east, and 0 for two coincident points
     */
    public static double bearing(double dx, double dz) {
        if (dx == 0 && dz == 0) return 0;
        return Math.toDegrees(Math.atan2(dx, -dz));
    }

    /**
     * A Minecraft yaw as a compass bearing.
     *
     * <p>A Minecraft yaw of 0 faces south and increases clockwise, so a yaw of 90 faces west. The
     * bearing equivalent is {@code yaw + 180} normalised, which is what a radar tail needs.
     */
    public static double yawToBearing(float yaw) {
        double bearing = yaw + 180.0;
        bearing %= 360.0;
        if (bearing < 0) bearing += 360.0;
        return bearing;
    }

    /** The nearest of eight compass names for a bearing, for text the app shows beside the angle. */
    public static String bearingName(double bearing) {
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west",
            "west", "north-west"};
        double normalised = bearing % 360.0;
        if (normalised < 0) normalised += 360.0;
        int index = (int) Math.round(normalised / 45.0) % 8;
        return names[index];
    }

    /** Rounds to one decimal, the precision the app displays and the snapshot sends. */
    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
