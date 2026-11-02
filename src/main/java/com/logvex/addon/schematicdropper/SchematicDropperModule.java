package com.logvex.addon.schematicdropper;

import com.logvex.addon.LogvexAddon;
import com.logvex.addon.schematicdropper.core.DropAction;
import com.logvex.addon.schematicdropper.core.DropConfig;
import com.logvex.addon.schematicdropper.core.DropEngine;
import com.logvex.addon.schematicdropper.core.EngineListener;
import com.logvex.addon.schematicdropper.core.EnvPort;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.InventoryMode;
import com.logvex.addon.schematicdropper.core.MaterialListParser;
import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.OrderingMode;
import com.logvex.addon.schematicdropper.core.ProgressSnapshot;
import com.logvex.addon.schematicdropper.core.ProgressTracker;
import com.logvex.addon.schematicdropper.core.RecoveryMode;
import com.logvex.addon.schematicdropper.core.Requirement;
import com.logvex.addon.schematicdropper.core.Rotation;
import com.logvex.addon.schematicdropper.core.RotationMode;
import com.logvex.addon.schematicdropper.core.StackStrategy;
import com.logvex.addon.schematicdropper.core.State;
import com.logvex.addon.schematicdropper.core.TargetMode;
import com.logvex.addon.schematicdropper.gui.MaterialOverviewScreen;
import com.logvex.addon.schematicdropper.mc.DropController;
import com.logvex.addon.schematicdropper.mc.MeteorRotationPort;
import com.logvex.addon.schematicdropper.mc.MinecraftEnv;
import com.logvex.addon.schematicdropper.mc.ServerInventoryTracker;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Drops the materials of a loaded schematic, one confirmed transfer at a time.
 *
 * <p>All decisions live in {@link DropEngine}; this class is the Minecraft/Meteor shell around it:
 * settings, event subscription and the run controls exposed to the ClickGUI, the material overview
 * and the HUD.
 */
public class SchematicDropperModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMaterials = settings.createGroup("Materials");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgProgress = settings.createGroup("Progress");

    // General

    private final Setting<Boolean> autoContinue = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-continue")
        .description("Keep dropping until every required material has been processed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> operationDelay = sgGeneral.add(new IntSetting.Builder()
        .name("operation-delay")
        .description("Milliseconds to wait between two drop operations.")
        .defaultValue(120)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Integer> retryLimit = sgGeneral.add(new IntSetting.Builder()
        .name("retry-limit")
        .description("How many times a failed operation is retried before the material is skipped.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> retryBackoff = sgGeneral.add(new IntSetting.Builder()
        .name("retry-backoff")
        .description("Milliseconds to wait before retrying a failed operation.")
        .defaultValue(250)
        .min(0)
        .sliderRange(0, 2000)
        .build()
    );

    private final Setting<RecoveryMode> recoveryMode = sgGeneral.add(new EnumSetting.Builder<RecoveryMode>()
        .name("recovery-mode")
        .description("Strict Order waits for the current material; Best Effort blocks it and moves on.")
        .defaultValue(RecoveryMode.STRICT_ORDER)
        .build()
    );

    private final Setting<Boolean> pauseOnMissing = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-missing")
        .description("Wait for materials instead of finishing when something is missing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> completionNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("completion-notification")
        .description("Announce in chat when every material has been dropped.")
        .defaultValue(true)
        .build()
    );

    // Materials

    private final Setting<List<String>> materialList = sgMaterials.add(new StringListSetting.Builder()
        .name("material-list")
        .description("Material list to drop. One entry per line, e.g. minecraft:cobblestone 128.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<OrderingMode> orderingMode = sgMaterials.add(new EnumSetting.Builder<OrderingMode>()
        .name("ordering-mode")
        .description("Order in which materials are processed. Stays fixed for the whole run.")
        .defaultValue(OrderingMode.SCHEMATIC_ORDER)
        .build()
    );

    // Rotation

    private final Setting<Boolean> rotationEnabled = sgRotation.add(new BoolSetting.Builder()
        .name("rotation-enabled")
        .description("Rotate the player's head toward the intended drop direction.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgRotation.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("Instant snaps to the target; Smooth interpolates.")
        .defaultValue(RotationMode.SMOOTH)
        .visible(rotationEnabled::get)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgRotation.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("Degrees per tick the head moves in Smooth mode.")
        .defaultValue(30)
        .min(1)
        .sliderRange(1, 180)
        .visible(() -> rotationEnabled.get() && rotationMode.get() == RotationMode.SMOOTH)
        .build()
    );

    private final Setting<Boolean> restoreRotation = sgRotation.add(new BoolSetting.Builder()
        .name("restore-rotation")
        .description("Restore the original head rotation when the run stops or completes.")
        .defaultValue(true)
        .visible(rotationEnabled::get)
        .build()
    );

    private final Setting<Integer> targetUpdate = sgRotation.add(new IntSetting.Builder()
        .name("target-update-interval")
        .description("Milliseconds between target re-evaluations.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 1000)
        .visible(rotationEnabled::get)
        .build()
    );

    private final Setting<Boolean> rotationDebug = sgRotation.add(new BoolSetting.Builder()
        .name("rotation-debug")
        .description("Print rotation targets to chat.")
        .defaultValue(false)
        .visible(rotationEnabled::get)
        .build()
    );

    private final Setting<Double> dropPitch = sgRotation.add(new DoubleSetting.Builder()
        .name("drop-pitch-offset")
        .description("Vertical offset of the player-position target, in blocks.")
        .defaultValue(-1.0)
        .min(-3)
        .sliderRange(-3, 3)
        .visible(rotationEnabled::get)
        .build()
    );

    // Inventory

    private final Setting<InventoryMode> inventoryMode = sgInventory.add(new EnumSetting.Builder<InventoryMode>()
        .name("inventory-mode")
        .description("Silent clicks the screen handler directly; Open Inventory opens the inventory first.")
        .defaultValue(InventoryMode.SILENT)
        .build()
    );

    private final Setting<StackStrategy> stackStrategy = sgInventory.add(new EnumSetting.Builder<StackStrategy>()
        .name("stack-strategy")
        .description("Which stack of a material to use first.")
        .defaultValue(StackStrategy.LARGEST_FIRST)
        .build()
    );

    private final Setting<DropAction> dropAction = sgInventory.add(new EnumSetting.Builder<DropAction>()
        .name("drop-action")
        .description("Drop One throws single items; Drop Full Stack throws whole stacks when allowed.")
        .defaultValue(DropAction.DROP_ONE)
        .build()
    );

    private final Setting<Integer> syncTimeout = sgInventory.add(new IntSetting.Builder()
        .name("sync-timeout")
        .description("Milliseconds to wait for the server to confirm an operation.")
        .defaultValue(1500)
        .min(200)
        .sliderRange(200, 5000)
        .build()
    );

    private final Setting<Integer> rescanInterval = sgInventory.add(new IntSetting.Builder()
        .name("rescan-interval")
        .description("Milliseconds between inventory rescans while waiting for materials.")
        .defaultValue(1000)
        .min(100)
        .sliderRange(100, 5000)
        .build()
    );

    // Targeting

    private final Setting<TargetMode> targetMode = sgTargeting.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("Where to aim the drop.")
        .defaultValue(TargetMode.PLAYER_POSITION)
        .visible(rotationEnabled::get)
        .build()
    );

    private final Setting<Boolean> validateTarget = sgTargeting.add(new BoolSetting.Builder()
        .name("validate-target")
        .description("Skip rotation when the target is invalid instead of rotating to bad coordinates.")
        .defaultValue(true)
        .build()
    );

    // Progress

    private final Setting<Boolean> hudEnabled = sgProgress.add(new BoolSetting.Builder()
        .name("hud-enabled")
        .description("Show the progress HUD element.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyProgress = sgProgress.add(new BoolSetting.Builder()
        .name("notify-progress")
        .description("Report each confirmed drop in chat.")
        .defaultValue(false)
        .build()
    );

    // Runtime

    private final ServerInventoryTracker serverTracker = new ServerInventoryTracker();
    private final MeteorRotationPort rotationPort = new MeteorRotationPort();
    private final ProgressTracker tracker = new ProgressTracker();
    private final DropController dropController = new DropController(serverTracker, inventoryMode::get);
    private final EnvPort env;
    private final DropEngine engine;

    private MaterialPlan plan = new MaterialPlan();
    private String lastFailure;
    private long lastRotationDebug;

    public SchematicDropperModule() {
        super(LogvexAddon.CATEGORY, "schematic-dropper", "Automated schematic material dropping.");

        this.env = new MinecraftEnv(targetMode::get, (float) (double) dropPitch.get());
        this.engine = new DropEngine(env, rotationPort, dropController, tracker, new Listener());
    }

    @Override
    public void onActivate() {
        MeteorClient.EVENT_BUS.subscribe(serverTracker);

        rebuildPlan();
        serverTracker.reset();
        rotationPort.reset();
        engine.setConfig(config());
        engine.setStackStrategy(stackStrategy.get());

        if (hudEnabled.get()) SchematicProgressHud.enable();
        engine.start(System.currentTimeMillis());
    }

    @Override
    public void onDeactivate() {
        engine.stop();
        SchematicProgressHud.disable();
        MeteorClient.EVENT_BUS.unsubscribe(serverTracker);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        engine.setConfig(config());
        engine.setStackStrategy(stackStrategy.get());
        engine.tick(System.currentTimeMillis());

        if (rotationDebug.get()) debugRotation();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        serverTracker.reset();
        engine.onWorldChanged();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        engine.onWorldChanged();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WHorizontalList run = list.add(theme.horizontalList()).expandX().widget();
        WButton start = run.add(theme.button("Start")).expandX().widget();
        start.action = this::startRun;
        WButton pause = run.add(theme.button("Pause")).expandX().widget();
        pause.action = this::pauseRun;
        WButton resume = run.add(theme.button("Resume")).expandX().widget();
        resume.action = this::resumeRun;

        WHorizontalList run2 = list.add(theme.horizontalList()).expandX().widget();
        WButton stop = run2.add(theme.button("Stop")).expandX().widget();
        stop.action = this::stopRun;
        WButton reset = run2.add(theme.button("Reset")).expandX().widget();
        reset.action = this::resetProgress;
        WButton rescan = run2.add(theme.button("Rescan")).expandX().widget();
        rescan.action = this::rescanInventory;

        WButton overview = list.add(theme.button("Material Overview")).expandX().widget();
        overview.action = this::openMaterialOverview;

        return list;
    }

    private DropConfig config() {
        return new DropConfig(
            autoContinue.get(),
            operationDelay.get(),
            retryLimit.get(),
            retryBackoff.get(),
            rescanInterval.get(),
            pauseOnMissing.get(),
            rotationEnabled.get(),
            rotationMode.get(),
            (float) (double) rotationSpeed.get(),
            restoreRotation.get(),
            targetMode.get(),
            targetUpdate.get(),
            orderingMode.get(),
            recoveryMode.get(),
            inventoryMode.get(),
            dropAction.get(),
            stackStrategy.get(),
            syncTimeout.get()
        );
    }

    /** Re-reads the material list setting into the plan used by the engine. */
    public void rebuildPlan() {
        List<Requirement> parsed = MaterialListParser.parse(materialList.get());
        plan = new MaterialPlan();
        plan.set(parsed);
        tracker.setPlan(plan);
    }

    // ---------------------------------------------------------------- controls

    public void startRun() {
        if (!isActive()) {
            error("Enable the module before starting a run.");
            return;
        }
        if (engine.state().isRunning()) {
            error("A run is already in progress.");
            return;
        }
        rebuildPlan();
        if (plan.isEmpty()) {
            error("No materials configured. Add entries to the material list.");
            return;
        }
        engine.start(System.currentTimeMillis());
    }

    public void pauseRun() {
        engine.pause();
    }

    public void resumeRun() {
        engine.resume(System.currentTimeMillis());
    }

    public void stopRun() {
        engine.stop();
    }

    public void resetProgress() {
        engine.resetProgress();
    }

    public void rescanInventory() {
        rebuildPlan();
        engine.requestRescan(System.currentTimeMillis());
    }

    public void openMaterialOverview() {
        rebuildPlan();
        mc.setScreen(new MaterialOverviewScreen(this, tracker));
    }

    public ProgressSnapshot snapshot() {
        return tracker.snapshot();
    }

    public ProgressTracker tracker() {
        return tracker;
    }

    public State runState() {
        return engine.state();
    }

    public String lastFailure() {
        return lastFailure;
    }

    public boolean isHudEnabled() {
        return hudEnabled.get();
    }

    public void setHudEnabled(boolean enabled) {
        hudEnabled.set(enabled);
        if (enabled) SchematicProgressHud.enable();
        else SchematicProgressHud.disable();
    }

    private void debugRotation() {
        long now = System.currentTimeMillis();
        if (now - lastRotationDebug < 500) return;
        lastRotationDebug = now;

        Rotation target = env.desiredRotation();
        Rotation current = env.currentRotation();
        if (target == null) {
            info("rotation: no valid target");
            return;
        }
        info(String.format("rotation: target %.1f/%.1f current %.1f/%.1f",
            target.yaw(), target.pitch(), current.yaw(), current.pitch()));
    }

    private void error(String message) {
        warning("%s", Formatting.RED + "[SchematicDropper] " + message);
    }

    private void info(String message) {
        warning("%s", "[SchematicDropper] " + message);
    }

    private final class Listener implements EngineListener {
        @Override
        public void onStateChanged(State state) {
            if (notifyProgress.get()) info("state: " + state);
        }

        @Override
        public void onConfirmed(String itemId, int amount, int remaining) {
            if (notifyProgress.get()) {
                info(String.format("dropped %d x %s (%d remaining)", amount, itemId, remaining));
            }
        }

        @Override
        public void onFailure(FailureType type, String detail) {
            lastFailure = type + ": " + detail;
            if (notifyProgress.get()) error(type + " - " + detail);
        }

        @Override
        public void onCompleted(long totalDropped, long elapsedMs) {
            if (completionNotification.get()) {
                warning(Formatting.GREEN + "[SchematicDropper] Complete: " + totalDropped
                    + " items in " + String.format("%.1f", elapsedMs / 1000.0) + "s");
            }
        }

        @Override
        public void onFeedback(String message) {
            if (notifyProgress.get()) info(message);
        }
    }
}
