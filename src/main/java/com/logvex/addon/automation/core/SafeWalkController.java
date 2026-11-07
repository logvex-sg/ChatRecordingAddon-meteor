package com.logvex.addon.automation.core;

/**
 * Decides whether the player is standing somewhere they could fall from.
 *
 * <p>Given the solidity of the blocks around and below the player, the controller decides whether
 * movement in a given direction would step into a drop. It does not move the player itself: it only
 * answers the question, so the Minecraft layer can suppress input at the moment it matters. The
 * rule is conservative — it only flags a step when the block under the destination is not solid and
 * the player is not already falling — so it never freezes ordinary walking.
 */
public final class SafeWalkController {
    private boolean enabled;
    private int maxDrop;
    private boolean sneakAtEdge;

    public SafeWalkController(int maxDrop, boolean sneakAtEdge) {
        this.maxDrop = Math.max(0, maxDrop);
        this.sneakAtEdge = sneakAtEdge;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The maximum drop, in blocks, that is still considered safe. */
    public void setMaxDrop(int value) {
        this.maxDrop = Math.max(0, value);
    }

    public int maxDrop() {
        return maxDrop;
    }

    public void setSneakAtEdge(boolean value) {
        this.sneakAtEdge = value;
    }

    public boolean sneakAtEdge() {
        return sneakAtEdge;
    }

    /**
     * Whether a step in the given direction would leave the player over a drop.
     *
     * @param groundBelow    whether the block directly under the player is solid
     * @param aheadSolid     whether the block at the destination is solid
     * @param dropAhead      how many blocks down the first solid ground ahead is; a large value or
     *                       {@code Integer.MAX_VALUE} means no ground was found within the search
     * @param onGround       whether the player is currently standing on something
     * @param movingForward  whether the player is trying to move forward
     */
    public boolean wouldFall(boolean groundBelow, boolean aheadSolid, int dropAhead,
                             boolean onGround, boolean movingForward) {
        if (!enabled || !movingForward || !onGround) return false;
        if (!groundBelow) return false;
        if (aheadSolid) return false;
        return dropAhead > maxDrop;
    }

    public String status() {
        return enabled ? "on (max drop " + maxDrop + (sneakAtEdge ? ", sneak at edge" : "") + ")" : "off";
    }
}
