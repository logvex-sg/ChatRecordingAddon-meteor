package com.logvex.addon.framework.pathing;

import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.Settings;
import net.minecraft.util.math.BlockPos;

/** A navigation backend: Baritone when it is installed, otherwise a no-op. */
public interface IPathManager {
    String getName();

    boolean isPathing();

    void pause();

    void resume();

    void stop();

    default void moveTo(BlockPos pos) {
        moveTo(pos, false);
    }

    void moveTo(BlockPos pos, boolean ignoreY);

    float getTargetYaw();

    float getTargetPitch();

    ISettings getSettings();

    /** The four navigation preferences the bot surfaces as module settings. */
    interface ISettings {
        Settings get();

        Setting<Boolean> getWalkOnWater();

        Setting<Boolean> getWalkOnLava();

        Setting<Boolean> getStep();

        Setting<Boolean> getNoFall();

        void save();
    }
}
