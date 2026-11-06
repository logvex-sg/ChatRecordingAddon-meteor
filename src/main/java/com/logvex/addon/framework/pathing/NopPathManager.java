package com.logvex.addon.framework.pathing;

import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.Settings;
import net.minecraft.util.math.BlockPos;

/** Used when no navigation backend is installed. */
public final class NopPathManager implements IPathManager {
    private final ISettings settings = new EmptySettings();

    @Override
    public String getName() {
        return "none";
    }

    @Override
    public boolean isPathing() {
        return false;
    }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void moveTo(BlockPos pos, boolean ignoreY) {
    }

    @Override
    public float getTargetYaw() {
        return 0;
    }

    @Override
    public float getTargetPitch() {
        return 0;
    }

    @Override
    public ISettings getSettings() {
        return settings;
    }

    private static final class EmptySettings implements ISettings {
        private final Settings settings = new Settings();
        private final Setting<Boolean> walkOnWater = register("walk-on-water");
        private final Setting<Boolean> walkOnLava = register("walk-on-lava");
        private final Setting<Boolean> step = register("step");
        private final Setting<Boolean> noFall = register("no-fall");

        private Setting<Boolean> register(String name) {
            return settings.getDefaultGroup().add(
                new com.logvex.addon.framework.settings.BoolSetting.Builder().name(name).build());
        }

        @Override
        public Settings get() {
            return settings;
        }

        @Override
        public Setting<Boolean> getWalkOnWater() {
            return walkOnWater;
        }

        @Override
        public Setting<Boolean> getWalkOnLava() {
            return walkOnLava;
        }

        @Override
        public Setting<Boolean> getStep() {
            return step;
        }

        @Override
        public Setting<Boolean> getNoFall() {
            return noFall;
        }

        @Override
        public void save() {
        }
    }
}
