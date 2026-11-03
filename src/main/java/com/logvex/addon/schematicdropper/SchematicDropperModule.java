package com.logvex.addon.schematicdropper;

import com.logvex.addon.LogAddon;
import com.logvex.addon.common.Messages;
import com.logvex.addon.schematicdropper.core.AcquisitionConfig;
import com.logvex.addon.schematicdropper.core.AcquisitionEngine;
import com.logvex.addon.schematicdropper.core.AcquisitionListener;
import com.logvex.addon.schematicdropper.core.AcquisitionSnapshot;
import com.logvex.addon.schematicdropper.core.AcquisitionState;
import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.ConflictPolicy;
import com.logvex.addon.schematicdropper.core.DropAction;
import com.logvex.addon.schematicdropper.core.DropConfig;
import com.logvex.addon.schematicdropper.core.DropEngine;
import com.logvex.addon.schematicdropper.core.EngineListener;
import com.logvex.addon.schematicdropper.core.EnvPort;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.InventoryMode;
import com.logvex.addon.schematicdropper.core.MaterialListImporter;
import com.logvex.addon.schematicdropper.core.MaterialListParser;
import com.logvex.addon.schematicdropper.core.MaterialPlan;
import com.logvex.addon.schematicdropper.core.OrderingMode;
import com.logvex.addon.schematicdropper.core.PlacementConfig;
import com.logvex.addon.schematicdropper.core.ProgressSnapshot;
import com.logvex.addon.schematicdropper.core.ProgressTracker;
import com.logvex.addon.schematicdropper.core.RecoveryMode;
import com.logvex.addon.schematicdropper.core.Requirement;
import com.logvex.addon.schematicdropper.core.Rotation;
import com.logvex.addon.schematicdropper.core.RotationMode;
import com.logvex.addon.schematicdropper.core.SchematicFormat;
import com.logvex.addon.schematicdropper.core.StackStrategy;
import com.logvex.addon.schematicdropper.core.State;
import com.logvex.addon.schematicdropper.core.TargetMode;
import com.logvex.addon.schematicdropper.gui.AcquisitionScreen;
import com.logvex.addon.schematicdropper.gui.BuildScreen;
import com.logvex.addon.schematicdropper.gui.MaterialOverviewScreen;
import com.logvex.addon.schematicdropper.mc.CreativeInventoryController;
import com.logvex.addon.schematicdropper.mc.CreativeMinecraftEnv;
import com.logvex.addon.schematicdropper.mc.DropBridge;
import com.logvex.addon.schematicdropper.mc.DropController;
import com.logvex.addon.schematicdropper.mc.MeteorRotationPort;
import com.logvex.addon.schematicdropper.mc.MinecraftEnv;
import com.logvex.addon.schematicdropper.mc.RegistryItemResolver;
import com.logvex.addon.schematicdropper.mc.SchematicFileImport;
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
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final SettingGroup sgAcquisition = settings.createGroup("Creative Acquisition");
    private final SettingGroup sgBuild = settings.createGroup("Schematic Building");

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

    // Creative Acquisition

    private final Setting<Boolean> autoAcquire = sgAcquisition.add(new BoolSetting.Builder()
        .name("auto-acquire")
        .description("Create missing materials automatically while in Creative mode.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoDrop = sgAcquisition.add(new BoolSetting.Builder()
        .name("auto-drop")
        .description("Drop acquired batches through the normal dropping workflow so large lists fit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> acquireDelay = sgAcquisition.add(new IntSetting.Builder()
        .name("acquire-delay")
        .description("Milliseconds between two creative gives.")
        .defaultValue(80)
        .min(0)
        .sliderRange(0, 500)
        .build()
    );

    private final Setting<Integer> acquireRetryLimit = sgAcquisition.add(new IntSetting.Builder()
        .name("acquire-retry-limit")
        .description("How many times a rejected creative give is retried before the material waits.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> acquireSyncTimeout = sgAcquisition.add(new IntSetting.Builder()
        .name("acquire-sync-timeout")
        .description("Milliseconds to wait for the server to confirm a creative give.")
        .defaultValue(1500)
        .min(100)
        .sliderRange(100, 10000)
        .build()
    );

    private final Setting<Integer> acquireScanInterval = sgAcquisition.add(new IntSetting.Builder()
        .name("acquire-scan-interval")
        .description("Milliseconds between inventory re-scans while waiting for space.")
        .defaultValue(500)
        .min(50)
        .sliderRange(50, 5000)
        .build()
    );

    // Schematic building

    private final Setting<Boolean> buildEnabled = sgBuild.add(new BoolSetting.Builder()
        .name("build-enabled")
        .description("Place a loaded schematic block by block, verifying every placement.")
        .defaultValue(false)
        .build()
    );

    private final Setting<BuildOriginMode> buildOriginMode = sgBuild.add(new EnumSetting.Builder<BuildOriginMode>()
        .name("origin")
        .description("Where the schematic is anchored.")
        .defaultValue(BuildOriginMode.PLAYER)
        .build()
    );

    private final Setting<Integer> buildOriginX = sgBuild.add(new IntSetting.Builder()
        .name("origin-x")
        .description("X coordinate used when Origin is set to Custom.")
        .defaultValue(0)
        .sliderRange(-30000000, 30000000)
        .visible(() -> buildOriginMode.get() == BuildOriginMode.CUSTOM)
        .build()
    );

    private final Setting<Integer> buildOriginY = sgBuild.add(new IntSetting.Builder()
        .name("origin-y")
        .description("Y coordinate used when Origin is set to Custom.")
        .defaultValue(64)
        .sliderRange(-64, 320)
        .visible(() -> buildOriginMode.get() == BuildOriginMode.CUSTOM)
        .build()
    );

    private final Setting<Integer> buildOriginZ = sgBuild.add(new IntSetting.Builder()
        .name("origin-z")
        .description("Z coordinate used when Origin is set to Custom.")
        .defaultValue(0)
        .sliderRange(-30000000, 30000000)
        .visible(() -> buildOriginMode.get() == BuildOriginMode.CUSTOM)
        .build()
    );

    private final Setting<ConflictPolicy> conflictPolicy = sgBuild.add(new EnumSetting.Builder<ConflictPolicy>()
        .name("conflicts")
        .description("What to do when a block that is not part of the schematic is in the way.")
        .defaultValue(ConflictPolicy.SKIP)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgBuild.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many placements to attempt per tick.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> buildRange = sgBuild.add(new IntSetting.Builder()
        .name("range")
        .description("Maximum distance in blocks the player may place from.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> buildCheckpoints = sgBuild.add(new BoolSetting.Builder()
        .name("checkpoints")
        .description("Save progress so an interrupted build can resume safely.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyBuild = sgBuild.add(new BoolSetting.Builder()
        .name("notify-build")
        .description("Report build progress and completion in chat.")
        .defaultValue(true)
        .build()
    );

    // Runtime

    private final ServerInventoryTracker serverTracker = new ServerInventoryTracker();
    private final MeteorRotationPort rotationPort = new MeteorRotationPort();
    private final ProgressTracker tracker = new ProgressTracker();
    private final DropController dropController = new DropController(serverTracker, inventoryMode::get);
    private final EnvPort env;
    private final DropEngine engine;

    private final CreativeMinecraftEnv acquisitionEnv = new CreativeMinecraftEnv();
    private final CreativeInventoryController creativeController = new CreativeInventoryController();
    private final DropBridge dropBridge;
    private final AcquisitionEngine acquisition;
    private final SchematicBuildController build;

    private MaterialPlan plan = new MaterialPlan();
    private int lastSchematicGeneration;
    private String lastFailure;
    private long lastRotationDebug;

    /** Maximum invalid entries listed in chat; the rest go to the debug log. */
    private static final int MAX_INVALID_REPORTED = 10;

    private SchematicFileImport pendingImport;
    private MaterialListImporter.Result lastImport;
    private String lastImportName = "material list";
    private int lastImportInvalid;

    public SchematicDropperModule() {
        super(LogAddon.CATEGORY, "schematic-dropper", "Automated schematic material dropping.");

        this.env = new MinecraftEnv(targetMode::get, (float) (double) dropPitch.get());
        this.engine = new DropEngine(env, rotationPort, dropController, tracker, new Listener());

        // A drain temporarily takes over the shared tracker: the bridge loads the batch, starts
        // the drop engine, and reports back the quantities it confirmed. Rotation is restored
        // normally because the drain ends through the engine's own completion path.
        this.dropBridge = new DropBridge(tracker, acquisitionEnv::countOf,
            this::startDropBatch, this::stopRun);
        this.acquisition = new AcquisitionEngine(acquisitionEnv, creativeController, dropBridge,
            new AcquisitionListenerImpl());
        this.build = new SchematicBuildController(new BuildFeedback(),
            MeteorClient.FOLDER.toPath().resolve("schematicdropper").resolve("checkpoints"));
    }

    @Override
    public void onActivate() {
        MeteorClient.EVENT_BUS.subscribe(serverTracker);
        MeteorClient.EVENT_BUS.subscribe(creativeController);

        rebuildPlan();
        serverTracker.reset();
        rotationPort.reset();
        creativeController.reset();
        engine.setConfig(config());
        engine.setStackStrategy(stackStrategy.get());
        acquisition.setPlan(plan);
        acquisition.setConfig(acquisitionConfig());

        if (hudEnabled.get()) SchematicProgressHud.enable();
        build.activate();
        build.setConfig(buildConfig());
        build.setOrigin(currentBuildOrigin());
        if (autoAcquire.get()) {
            // Acquisition owns the pipeline; the drop engine is started by the drains it requests.
            acquisition.start(System.currentTimeMillis());
        } else {
            engine.start(System.currentTimeMillis());
        }
    }

    @Override
    public void onDeactivate() {
        engine.stop();
        acquisition.stop();
        build.saveCheckpointNow();
        build.deactivate();
        SchematicProgressHud.disable();
        cancelImport();
        MeteorClient.EVENT_BUS.unsubscribe(creativeController);
        MeteorClient.EVENT_BUS.unsubscribe(serverTracker);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        engine.setConfig(config());
        engine.setStackStrategy(stackStrategy.get());
        engine.tick(System.currentTimeMillis());

        if (dropBridge.isActive() && !engine.state().isRunning()) dropBridge.finish();

        acquisition.setConfig(acquisitionConfig());
        acquisition.setPlan(plan);
        acquisition.tick(System.currentTimeMillis());

        build.setConfig(buildConfig());
        build.setOrigin(currentBuildOrigin());
        build.tick(System.currentTimeMillis());
        syncSchematicMaterials();

        if (pendingImport != null) stepImport();

        if (rotationDebug.get()) debugRotation();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        serverTracker.reset();
        creativeController.reset();
        engine.onWorldChanged();
        acquisition.onWorldChanged();
        build.onWorldChanged();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        engine.onWorldChanged();
        acquisition.onWorldChanged();
        build.onWorldChanged();
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

        WHorizontalList run3 = list.add(theme.horizontalList()).expandX().widget();
        WButton acquire = run3.add(theme.button("Acquire Materials")).expandX().widget();
        acquire.action = this::startAcquisition;
        WButton acquisitionScreen = run3.add(theme.button("Acquisition...")).expandX().widget();
        acquisitionScreen.action = this::openAcquisitionScreen;

        WButton importFile = list.add(theme.button("Import Material List...")).expandX().widget();
        importFile.action = this::importMaterialFile;

        WButton overview = list.add(theme.button("Material Overview")).expandX().widget();
        overview.action = this::openMaterialOverview;

        WHorizontalList buildRow = list.add(theme.horizontalList()).expandX().widget();
        WButton loadSchematic = buildRow.add(theme.button("Load Schematic...")).expandX().widget();
        loadSchematic.action = this::loadSchematicFile;
        WButton startBuild = buildRow.add(theme.button("Start Build")).expandX().widget();
        startBuild.action = this::startBuild;

        WHorizontalList buildRow2 = list.add(theme.horizontalList()).expandX().widget();
        WButton pauseBuild = buildRow2.add(theme.button("Pause")).expandX().widget();
        pauseBuild.action = this::pauseBuild;
        WButton resumeBuild = buildRow2.add(theme.button("Resume")).expandX().widget();
        resumeBuild.action = this::resumeBuild;
        WButton stopBuild = buildRow2.add(theme.button("Stop")).expandX().widget();
        stopBuild.action = this::stopBuild;

        WButton buildScreen = list.add(theme.button("Schematic Builder...")).expandX().widget();
        buildScreen.action = this::openBuildScreen;

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

    // ---------------------------------------------------------------- config

    private AcquisitionConfig acquisitionConfig() {
        return new AcquisitionConfig(
            autoAcquire.get(),
            autoDrop.get(),
            acquireDelay.get(),
            acquireRetryLimit.get(),
            retryBackoff.get(),
            acquireSyncTimeout.get(),
            acquireScanInterval.get()
        );
    }

    /** Starts a drop batch for the acquisition workflow. @return false when nothing can be dropped. */
    private boolean startDropBatch() {
        if (!dropBridge.prepare(plan)) return false;
        engine.start(System.currentTimeMillis());
        return true;
    }

    // ------------------------------------------------------------ schematic build

    private PlacementConfig buildConfig() {
        return new PlacementConfig(
            blocksPerTick.get(),
            120,
            retryLimit.get(),
            retryBackoff.get(),
            1500,
            rescanInterval.get(),
            buildRange.get(),
            conflictPolicy.get(),
            true,
            true,
            1
        );
    }

    /** Resolves the configured origin to an absolute position. */
    private BlockPos3 currentBuildOrigin() {
        return switch (buildOriginMode.get()) {
            case PLAYER -> {
                if (mc.player == null) yield new BlockPos3(0, 0, 0);
                yield new BlockPos3((int) Math.floor(mc.player.getX()),
                    (int) Math.floor(mc.player.getY()), (int) Math.floor(mc.player.getZ()));
            }
            case CUSTOM -> new BlockPos3(buildOriginX.get(), buildOriginY.get(), buildOriginZ.get());
        };
    }

    /** Opens the file picker and loads the chosen schematic asynchronously. */
    public void loadSchematicFile() {
        String path = chooseSchematicFile();
        if (path == null) return;
        File file = new File(path);
        if (!file.isFile()) {
            error("That file could not be read.");
            return;
        }
        build.loadAsync(file.toPath());
        info("Loading " + file.getName() + "...");
    }

    public void startBuild() {
        if (!isActive()) {
            error("Enable the module before starting a build.");
            return;
        }
        if (!buildEnabled.get()) {
            error("Enable \"build-enabled\" in Schematic Building first.");
            return;
        }
        if (!build.hasSchematic()) {
            error("Load a schematic file first.");
            return;
        }
        build.setOrigin(currentBuildOrigin());
        build.startBuild(System.currentTimeMillis());
    }

    public void pauseBuild() {
        build.pause();
    }

    public void resumeBuild() {
        build.resume(System.currentTimeMillis());
    }

    public void stopBuild() {
        build.stop(System.currentTimeMillis());
    }

    public void retryFailedBlocks() {
        build.retryFailed();
    }

    public SchematicBuildController buildController() {
        return build;
    }

    /**
     * Mirrors a freshly loaded schematic into the material list exactly once per load, so the same
     * materials can be dropped or acquired through the existing workflows. It never runs on a quiet
     * tick, so an imported material list is left alone.
     */
    private void syncSchematicMaterials() {
        if (!build.hasSchematic()) return;
        if (build.loadGeneration() == lastSchematicGeneration) return;
        lastSchematicGeneration = build.loadGeneration();

        List<Requirement> derived = build.schematic().toMaterialPlan().requirements();
        List<String> entries = new ArrayList<>(derived.size());
        for (Requirement r : derived) entries.add(r.itemId() + " " + r.required());
        materialList.set(entries);
        rebuildPlan();
        info("Material list updated from " + build.schematic().name() + ": "
            + Messages.plural(derived.size(), "material") + ".");
    }

    private String chooseSchematicFile() {
        PointerBuffer filters = MemoryUtil.memAllocPointer(3);
        filters.put(MemoryUtil.memUTF8("*.schem"));
        filters.put(MemoryUtil.memUTF8("*.schematic"));
        filters.put(MemoryUtil.memUTF8("*.litematic"));
        filters.flip();
        try {
            return TinyFileDialogs.tinyfd_openFileDialog(
                "Select schematic",
                new File(MeteorClient.FOLDER, "schematicdropper").getAbsolutePath(),
                filters,
                null,
                false
            );
        } finally {
            MemoryUtil.memFree(filters);
        }
    }

    /** Routes the build controller's output through the module's chat formatting. */
    private final class BuildFeedback implements SchematicBuildController.Feedback {
        @Override
        public void info(String message) {
            if (notifyBuild.get()) SchematicDropperModule.this.info(message);
        }

        @Override
        public void warn(String message) {
            if (notifyBuild.get()) warning("%s", Formatting.YELLOW + Messages.PREFIX + message);
        }

        @Override
        public void error(String message) {
            SchematicDropperModule.this.error(message);
        }

        @Override
        public void debug(String message) {
            SchematicDropperModule.this.debug(message);
        }
    }

    /** Where a schematic build is anchored. */
    public enum BuildOriginMode {
        PLAYER,
        CUSTOM;

        @Override
        public String toString() {
            return switch (this) {
                case PLAYER -> "Player position";
                case CUSTOM -> "Custom coordinates";
            };
        }
    }

    // ---------------------------------------------------------------- controls

    public void startRun() {
        if (!isActive()) {
            error("Enable the module before starting a run.");
            return;
        }
        if (acquisition.state().isRunning()) {
            error("Acquisition is running. Stop it before starting a drop run.");
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

    // ---------------------------------------------------------------- acquisition

    /** Starts automatic Creative acquisition from the current material list. */
    public void startAcquisition() {
        if (!isActive()) {
            error("Enable the module before starting acquisition.");
            return;
        }
        rebuildPlan();
        if (plan.isEmpty()) {
            error("No materials imported. Import a material list first.");
            return;
        }
        engine.stop();
        acquisition.setPlan(plan);
        acquisition.setConfig(acquisitionConfig());
        acquisition.start(System.currentTimeMillis());
    }

    public void pauseAcquisition() {
        acquisition.pause();
    }

    public void resumeAcquisition() {
        acquisition.resume(System.currentTimeMillis());
    }

    public void stopAcquisition() {
        acquisition.stop();
    }

    public void retryAcquisition() {
        acquisition.retryFailed();
    }

    public void restartCurrentAcquisition() {
        acquisition.restartCurrent();
    }

    public AcquisitionSnapshot acquisitionSnapshot() {
        return acquisition.snapshot();
    }

    public AcquisitionState acquisitionState() {
        return acquisition.state();
    }

    /** True when the module is currently acquiring materials. */
    public boolean isAcquiring() {
        return acquisition.state().isRunning();
    }

    public MaterialListImporter.Result lastImport() {
        return lastImport;
    }

    public String lastImportName() {
        return lastImportName;
    }

    public int lastImportInvalid() {
        return lastImportInvalid;
    }

    /** Opens the native file picker and starts a progressive import of the chosen file. */
    public void importMaterialFile() {
        String path = chooseFile();
        if (path == null) return;

        File file = new File(path);
        if (!file.isFile()) {
            error("That file could not be read.");
            return;
        }

        // A schematic is imported by loading it: the block counts become the material list, so the
        // same file can drive dropping, acquisition and building.
        if (isSchematicFile(file.getName())) {
            build.loadAsync(file.toPath());
            info("Importing " + file.getName() + "...");
            return;
        }

        cancelImport();
        pendingImport = new SchematicFileImport(file.toPath(), new RegistryItemResolver());
        lastImportName = file.getName();
        info("Importing " + file.getName() + "...");
    }

    private static boolean isSchematicFile(String fileName) {
        SchematicFormat format = SchematicFormat.fromFileName(fileName);
        return format == SchematicFormat.SPONGE || format == SchematicFormat.MCEDIT
            || format == SchematicFormat.LITEMATIC;
    }

    /** Releases any in-progress import reader. */
    private void cancelImport() {
        if (pendingImport != null) {
            pendingImport.close();
            pendingImport = null;
        }
    }

    /** Advances a progressive import by one chunk; called from the client tick. */
    private void stepImport() {
        SchematicFileImport importTask = pendingImport;
        if (importTask == null) return;

        try {
            if (importTask.step()) return;
        } catch (Exception e) {
            pendingImport = null;
            importTask.close();
            error("Import failed: " + shortReason(e));
            return;
        }

        pendingImport = null;
        importTask.close();
        applyImport(importTask.result());
    }

    private void applyImport(MaterialListImporter.Result result) {
        lastImport = result;
        lastImportInvalid = result.invalid().size();

        if (result.isEmpty()) {
            error("No usable materials found in " + lastImportName + ".");
            return;
        }

        // The imported list replaces the module's material list so the existing drop workflow and
        // the acquisition workflow both operate on exactly what was imported, in file order.
        List<String> entries = new ArrayList<>(result.requirements().size());
        for (Requirement r : result.requirements()) {
            entries.add(r.itemId() + " " + r.required());
        }
        materialList.set(entries);
        rebuildPlan();

        info(SchematicFileImport.describe(result, lastImportName));

        // Invalid entries are worth showing, but a file full of them must not flood chat.
        int shown = 0;
        for (MaterialListImporter.InvalidEntry entry : result.invalid()) {
            if (shown++ >= MAX_INVALID_REPORTED) break;
            warning("%s", Formatting.YELLOW + Messages.PREFIX + lastImportName + " " + entry);
        }
        if (result.invalid().size() > MAX_INVALID_REPORTED) {
            warning("%s", Formatting.YELLOW + Messages.PREFIX
                + "Skipped " + Messages.number(result.invalid().size() - MAX_INVALID_REPORTED)
                + " more invalid entries; see the debug log.");
            for (MaterialListImporter.InvalidEntry entry : result.invalid()) {
                debug(lastImportName + " " + entry);
            }
        }
    }

    private String chooseFile() {
        PointerBuffer filters = MemoryUtil.memAllocPointer(6);
        filters.put(MemoryUtil.memUTF8("*.txt"));
        filters.put(MemoryUtil.memUTF8("*.md"));
        filters.put(MemoryUtil.memUTF8("*.csv"));
        filters.put(MemoryUtil.memUTF8("*.schem"));
        filters.put(MemoryUtil.memUTF8("*.schematic"));
        filters.put(MemoryUtil.memUTF8("*.litematic"));
        filters.flip();
        try {
            return TinyFileDialogs.tinyfd_openFileDialog(
                "Select material list or schematic",
                new File(MeteorClient.FOLDER, "schematicdropper").getAbsolutePath(),
                filters,
                null,
                false
            );
        } finally {
            MemoryUtil.memFree(filters);
        }
    }

    private String shortReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
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

    public void openAcquisitionScreen() {
        mc.setScreen(new AcquisitionScreen(this));
    }

    public void openBuildScreen() {
        mc.setScreen(new BuildScreen(this));
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
            debug("Rotation has no valid target.");
            return;
        }
        debug(String.format(Locale.ROOT, "Rotation target %.1f/%.1f, current %.1f/%.1f",
            target.yaw(), target.pitch(), current.yaw(), current.pitch()));
    }

    private final class Listener implements EngineListener {
        @Override
        public void onStateChanged(State state) {
            if (notifyProgress.get()) info("Dropper state: " + state + ".");
        }

        @Override
        public void onConfirmed(String itemId, int amount, int remaining) {
            if (!notifyProgress.get()) return;
            info(Messages.format("Dropped %s. %s left for %s.",
                Messages.plural(amount, "item"),
                Messages.number(remaining),
                Messages.prettyId(itemId)));
        }

        @Override
        public void onFailure(FailureType type, String detail) {
            lastFailure = type + ": " + detail;
            // Recoverable failures are normal operation (a stack moved, a scan was stale); they
            // must not spam chat. Only genuine problems are surfaced.
            if (type.recoverable()) {
                debug("Dropper recovered from " + type + ": " + detail);
                return;
            }
            error(describeFailure(type, detail));
        }

        @Override
        public void onCompleted(long totalDropped, long elapsedMs) {
            // A batch dropped for the acquisition workflow is an internal step, not a finished
            // run; announcing every batch would flood chat.
            if (acquisition.state().isRunning()) {
                debug("Dropped a batch of " + totalDropped + " items for acquisition.");
                return;
            }
            if (!completionNotification.get()) return;
            info(Messages.format("Dropping complete. %s in %s.",
                Messages.plural(totalDropped, "item"),
                Messages.duration(elapsedMs)));
        }

        @Override
        public void onFeedback(String message) {
            if (notifyProgress.get()) info(message);
        }
    }

    private final class AcquisitionListenerImpl implements AcquisitionListener {
        @Override
        public void onStateChanged(AcquisitionState state) {
            if (notifyProgress.get()) info("Acquisition state: " + state + ".");
        }

        @Override
        public void onAcquired(String itemId, int amount) {
            if (!notifyProgress.get()) return;
            info(Messages.format("Acquired %s of %s.",
                Messages.plural(amount, "item"),
                Messages.prettyId(itemId)));
        }

        @Override
        public void onFailure(FailureType type, String detail, boolean fatal) {
            lastFailure = type + ": " + detail;
            if (!fatal && type.recoverable()) {
                debug("Acquisition recovered from " + type + ": " + detail);
                return;
            }
            error(describeFailure(type, detail));
        }

        @Override
        public void onCompleted(int materials, long totalAcquired, long elapsedMs) {
            if (!completionNotification.get()) return;
            info(Messages.format("Acquisition complete. %s across %s in %s.",
                Messages.plural(totalAcquired, "item"),
                Messages.plural(materials, "material"),
                Messages.duration(elapsedMs)));
        }

        @Override
        public void onFeedback(String message) {
            if (notifyProgress.get()) info(message);
        }
    }

    // ---------------------------------------------------------------- output

    /**
     * Turns a failure classification into one actionable sentence. Technical detail stays in the
     * debug log; chat gets what the user can act on.
     */
    private String describeFailure(FailureType type, String detail) {
        return switch (type) {
            case NOT_CREATIVE -> "Creative mode is required to acquire materials.";
            case NO_CREATIVE_PERMISSION ->
                "The server refused creative inventory changes. You need creative permissions.";
            case UNKNOWN_ITEM -> "Unknown item id: " + detail;
            case INVENTORY_FULL -> "The inventory is full; waiting for space.";
            case INVALID_QUANTITY -> "Invalid material quantity: " + detail;
            case NOT_READY -> "Not ready yet; retrying.";
            case TARGET_INVALID -> "No valid target to drop toward.";
            case SCREEN_INVALID -> "An inventory screen is blocking the operation.";
            default -> type + ": " + detail;
        };
    }

    private void error(String message) {
        warning("%s", Formatting.RED + Messages.PREFIX + message);
    }

    private void info(String message) {
        warning("%s", Messages.PREFIX + message);
    }

    private void debug(String message) {
        LogAddon.LOG.debug("[SchematicDropper] {}", message);
    }
}
