package com.logvex.addon.framework.pathing;

import com.logvex.addon.LogAddon;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.Settings;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * A navigation backend that drives Baritone through reflection.
 *
 * <p>Baritone is not published to a public Maven, so it is not a compile dependency. Every call
 * here goes through reflection and is guarded: if a Baritone build is absent or exposes a different
 * shape, the manager reports {@link #getName()} as {@code "none"} and pathing silently falls back to
 * the built-in walker instead of failing.
 */
public final class BaritonePathManager implements IPathManager {
    private static final String BARITONE_API = "baritone.api.BaritoneAPI";

    private final Object primaryBaritone;
    private final Field rotationField;
    private final BaritoneSettings settings = new BaritoneSettings();

    private boolean pathingPaused;

    BaritonePathManager() throws ReflectiveOperationException {
        Class.forName(BARITONE_API);
        Object provider = Class.forName(BARITONE_API).getMethod("getProvider").invoke(null);
        this.primaryBaritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
        this.rotationField = findRotationField(primaryBaritone);

        // A process that pauses pathing while pathingPaused is set.
        Object controlManager = call(primaryBaritone, "getPathingControlManager");
        if (controlManager != null) {
            registerPauseProcess(controlManager);
        }
    }

    private static Field findRotationField(Object primaryBaritone) {
        Object lookBehavior = call(primaryBaritone, "getLookBehavior");
        if (lookBehavior == null) return null;
        for (Field field : lookBehavior.getClass().getDeclaredFields()) {
            if (field.getType().getName().equals("baritone.api.utils.Rotation")) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /** Registers a temporary process that requests a pause whenever {@code pathingPaused} is set. */
    private void registerPauseProcess(Object controlManager) {
        try {
            ClassLoader loader = controlManager.getClass().getClassLoader();
            Class<?> processInterface = Class.forName("baritone.api.process.IBaritoneProcess");
            Object commandType = Enum.valueOf(
                (Class<? extends Enum>) Class.forName("baritone.api.process.PathingCommandType"), "REQUEST_PAUSE");
            Class<?> commandClass = Class.forName("baritone.api.process.PathingCommand");
            var constructor = commandClass.getConstructor(Class.forName("baritone.api.pathing.goals.Goal"), commandType.getClass());

            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "isActive" -> pathingPaused;
                case "isTemporary" -> true;
                case "onTick" -> {
                    clearAllKeys();
                    yield constructor.newInstance(null, commandType);
                }
                case "displayName" -> "Log Addon pause";
                case "priority" -> 0.0;
                case "onLostControl" -> null;
                case "toString" -> "Log Addon pause";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };

            Object process = Proxy.newProxyInstance(loader, new Class<?>[]{processInterface}, handler);
            controlManager.getClass().getMethod("registerProcess", processInterface).invoke(controlManager, process);
        } catch (ReflectiveOperationException e) {
            LogAddon.LOG.warn("Baritone pause control unavailable: {}", e.toString());
        }
    }

    private void clearAllKeys() {
        Object inputOverride = call(primaryBaritone, "getInputOverrideHandler");
        if (inputOverride == null) return;
        try {
            inputOverride.getClass().getMethod("clearAllKeys").invoke(inputOverride);
        } catch (ReflectiveOperationException ignored) {
            // Older Baritone builds have no clearAllKeys; the pause still takes effect.
        }
    }

    private static Object call(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public String getName() {
        return "Baritone";
    }

    @Override
    public boolean isPathing() {
        Object behavior = call(primaryBaritone, "getPathingBehavior");
        Object result = call(behavior, "isPathing");
        return result instanceof Boolean b && b;
    }

    @Override
    public void pause() {
        pathingPaused = true;
    }

    @Override
    public void resume() {
        pathingPaused = false;
    }

    @Override
    public void stop() {
        Object behavior = call(primaryBaritone, "getPathingBehavior");
        if (behavior == null) return;
        try {
            behavior.getClass().getMethod("cancelEverything").invoke(behavior);
        } catch (ReflectiveOperationException e) {
            LogAddon.LOG.warn("Baritone cancel failed: {}", e.toString());
        }
    }

    @Override
    public void moveTo(BlockPos pos, boolean ignoreY) {
        Object process = call(primaryBaritone, "getCustomGoalProcess");
        if (process == null) return;
        try {
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Object goal = ignoreY
                ? Class.forName("baritone.api.pathing.goals.GoalXZ")
                    .getConstructor(int.class, int.class).newInstance(pos.getX(), pos.getZ())
                : Class.forName("baritone.api.pathing.goals.GoalGetToBlock")
                    .getConstructor(BlockPos.class).newInstance(pos);
            process.getClass().getMethod("setGoalAndPath", goalClass).invoke(process, goal);
        } catch (ReflectiveOperationException e) {
            LogAddon.LOG.warn("Baritone moveTo failed: {}", e.toString());
        }
    }

    @Override
    public float getTargetYaw() {
        return rotationComponent(true);
    }

    @Override
    public float getTargetPitch() {
        return rotationComponent(false);
    }

    private float rotationComponent(boolean yaw) {
        if (rotationField == null) return 0;
        Object lookBehavior = call(primaryBaritone, "getLookBehavior");
        if (lookBehavior == null) return 0;
        try {
            Object rotation = rotationField.get(lookBehavior);
            if (rotation == null) return 0;
            Object value = rotation.getClass().getMethod(yaw ? "getYaw" : "getPitch").invoke(rotation);
            return value instanceof Number n ? n.floatValue() : 0;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    @Override
    public ISettings getSettings() {
        return settings;
    }

    /** Maps the four bot settings onto the matching Baritone settings. */
    private static final class BaritoneSettings implements ISettings {
        private final Settings settings = new Settings();
        private final Setting<Boolean> walkOnWater;
        private final Setting<Boolean> walkOnLava;
        private final Setting<Boolean> step;
        private final Setting<Boolean> noFall;

        BaritoneSettings() {
            SettingGroup group = settings.getDefaultGroup();
            walkOnWater = group.add(bool("assumeWalkOnWater", "walk-on-water"));
            walkOnLava = group.add(bool("assumeWalkOnLava", "walk-on-lava"));
            step = group.add(bool("assumeStep", "step"));
            noFall = group.add(noFallSetting());
        }

        private Setting<Boolean> bool(String baritoneName, String name) {
            Boolean current = readBool(baritoneName, false);
            return new BoolSetting.Builder()
                .name(name)
                .defaultValue(current)
                .onChanged(value -> writeBaritone(baritoneName, value))
                .build();
        }

        /** Baritone stores no-fall as a fall-height ceiling rather than a flag. */
        private Setting<Boolean> noFallSetting() {
            return new BoolSetting.Builder()
                .name("no-fall")
                .defaultValue(readInt("maxFallHeightNoWater", 0) >= 255)
                .onChanged(value -> writeBaritone("maxFallHeightNoWater", value ? 159159 : 3))
                .build();
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
            try {
                Class<?> api = Class.forName(BARITONE_API);
                Object baritoneSettings = api.getMethod("getSettings").invoke(null);
                Class.forName("baritone.api.utils.SettingsUtil")
                    .getMethod("save", baritoneSettings.getClass()).invoke(null, baritoneSettings);
            } catch (ReflectiveOperationException e) {
                LogAddon.LOG.warn("Baritone settings save failed: {}", e.toString());
            }
        }

        private static Object baritoneSetting(String name) {
            try {
                Class<?> api = Class.forName(BARITONE_API);
                Object baritoneSettings = api.getMethod("getSettings").invoke(null);
                Field field = baritoneSettings.getClass().getField(name);
                return field.get(baritoneSettings);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        private static Boolean readBool(String name, boolean fallback) {
            Object setting = baritoneSetting(name);
            if (setting == null) return fallback;
            try {
                Object value = setting.getClass().getField("value").get(setting);
                return value instanceof Boolean b ? b : fallback;
            } catch (ReflectiveOperationException e) {
                return fallback;
            }
        }

        private static int readInt(String name, int fallback) {
            Object setting = baritoneSetting(name);
            if (setting == null) return fallback;
            try {
                Object value = setting.getClass().getField("value").get(setting);
                return value instanceof Number n ? n.intValue() : fallback;
            } catch (ReflectiveOperationException e) {
                return fallback;
            }
        }

        private static void writeBaritone(String name, Object value) {
            Object setting = baritoneSetting(name);
            if (setting == null) return;
            try {
                setting.getClass().getField("value").set(setting, value);
            } catch (ReflectiveOperationException e) {
                LogAddon.LOG.warn("Baritone setting {} write failed: {}", name, e.toString());
            }
        }
    }
}
