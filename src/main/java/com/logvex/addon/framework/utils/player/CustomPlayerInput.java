package com.logvex.addon.framework.utils.player;

import net.minecraft.client.input.Input;

/**
 * An {@link Input} the bot drives directly.
 *
 * <p>The player's real input is replaced with one of these while the bot walks, so the bot's
 * movement never fights the user's keyboard. {@link #stop} releases every key at once, which is how
 * a task releases control.
 */
public class CustomPlayerInput extends Input {
    @Override
    public void tick(boolean slowDown, float f) {
        movementForward = pressingForward == pressingBack ? 0.0F : (pressingForward ? 1.0F : -1.0F);
        movementSideways = pressingLeft == pressingRight ? 0.0F : (pressingLeft ? 1.0F : -1.0F);

        if (sneaking) {
            movementForward *= 0.3;
            movementSideways *= 0.3;
        }
    }

    public void stop() {
        pressingForward = false;
        pressingBack = false;
        pressingRight = false;
        pressingLeft = false;
        jumping = false;
        sneaking = false;
    }
}
