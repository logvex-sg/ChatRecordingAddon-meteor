package com.logvex.addon.bot;

import com.logvex.addon.LogAddon;
import com.logvex.addon.bot.core.BotClock;
import com.logvex.addon.bot.core.BotConfig;
import com.logvex.addon.bot.core.BotController;
import com.logvex.addon.bot.core.BotListener;
import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.bot.core.BotState;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.GoalManager;
import com.logvex.addon.bot.core.MemoryStore;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.exec.StandardExecutors;
import com.logvex.addon.bot.gui.BotDashboardScreen;
import com.logvex.addon.bot.gui.TaskManagerScreen;
import com.logvex.addon.bot.hud.BotStatusHud;
import com.logvex.addon.bot.mc.FileMemoryStore;
import com.logvex.addon.bot.mc.MinecraftActionPort;
import com.logvex.addon.bot.mc.MinecraftBuildPort;
import com.logvex.addon.bot.mc.MinecraftInventoryPort;
import com.logvex.addon.bot.mc.MinecraftItemKnowledge;
import com.logvex.addon.bot.mc.MinecraftNavPort;
import com.logvex.addon.bot.mc.MinecraftPlayerPort;
import com.logvex.addon.bot.mc.MinecraftRecipeSource;
import com.logvex.addon.bot.mc.MinecraftWorldPort;
import com.logvex.addon.schematicdropper.SchematicBuildController;
import com.logvex.addon.schematicdropper.SchematicDropperModule;
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
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.nio.file.Path;

/**
 * The autonomous bot.
 *
 * <p>This module is the Minecraft/Meteor shell around {@link BotController}. It owns the settings,
 * the event subscriptions, the live ports and the memory store; every decision lives in the
 * Minecraft-free core. The controller is advanced once per client tick and performs no blocking
 * work, so the game stays responsive while the bot runs.
 */
public class BotModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGathering = settings.createGroup("Gathering");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");
    private final SettingGroup sgMemory = settings.createGroup("Memory");
    private final SettingGroup sgFeedback = settings.createGroup("Feedback");
    private final SettingGroup sgHud = settings.createGroup("HUD");

    /** How much the bot says in chat. */
    public enum Verbosity {
        SILENT,
        NORMAL,
        VERBOSE
    }

    // General

    private final Setting<Boolean> autoStart = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-start")
        .description("Start working as soon as an objective is added.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopOnDisconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-disconnect")
        .description("Pause the bot when the world changes or the connection drops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> taskTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("task-timeout")
        .description("Seconds a single task may run before it is failed.")
        .defaultValue(120)
        .min(10)
        .sliderRange(10, 600)
        .build()
    );

    private final Setting<Integer> retryLimit = sgGeneral.add(new IntSetting.Builder()
        .name("retry-limit")
        .description("How many times a failing step is retried before the task fails.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    // Gathering

    private final Setting<Integer> gatherRadius = sgGathering.add(new IntSetting.Builder()
        .name("gather-radius")
        .description("How far the bot searches for blocks to gather.")
        .defaultValue(32)
        .min(4)
        .sliderRange(4, 128)
        .build()
    );

    private final Setting<Integer> scanResults = sgGathering.add(new IntSetting.Builder()
        .name("max-scan-results")
        .description("Upper bound on blocks returned by one search.")
        .defaultValue(512)
        .min(16)
        .sliderRange(16, 1024)
        .build()
    );

    private final Setting<Integer> minToolDurability = sgGathering.add(new IntSetting.Builder()
        .name("min-tool-durability")
        .description("Durability below which a tool is reported as worn.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> containerRadius = sgGathering.add(new IntSetting.Builder()
        .name("container-radius")
        .description("How far the bot searches for containers to store items in.")
        .defaultValue(16)
        .min(4)
        .sliderRange(4, 64)
        .build()
    );

    // Navigation

    private final Setting<Double> navigationTolerance = sgNavigation.add(new DoubleSetting.Builder()
        .name("arrival-tolerance")
        .description("How close counts as arrived, in blocks.")
        .defaultValue(2.0)
        .min(0.5)
        .sliderRange(0.5, 6)
        .build()
    );

    private final Setting<Double> maxTravelDistance = sgNavigation.add(new DoubleSetting.Builder()
        .name("max-travel-distance")
        .description("Longest route the bot will attempt, in blocks.")
        .defaultValue(256)
        .min(16)
        .sliderRange(16, 1024)
        .build()
    );

    // Safety

    private final Setting<Boolean> stopOnLowHealth = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-danger")
        .description("Pause the bot when health or food drops below the safety thresholds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> healthThreshold = sgSafety.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("Pause when health is at or below this many half-hearts.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> foodThreshold = sgSafety.add(new IntSetting.Builder()
        .name("food-threshold")
        .description("Pause when food is at or below this many shanks.")
        .defaultValue(6)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    // Performance

    private final Setting<Integer> actionCooldown = sgPerformance.add(new IntSetting.Builder()
        .name("action-cooldown")
        .description("Milliseconds between two bot actions.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Integer> maxTasksPerTick = sgPerformance.add(new IntSetting.Builder()
        .name("actions-per-tick")
        .description("How many bot actions may run per tick.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Boolean> lowPerformanceMode = sgPerformance.add(new BoolSetting.Builder()
        .name("low-performance-mode")
        .description("Halve the search radius and the per-tick budget on a slow machine.")
        .defaultValue(false)
        .build()
    );

    // Memory

    private final Setting<Boolean> persistMemory = sgMemory.add(new BoolSetting.Builder()
        .name("persist-memory")
        .description("Save waypoints and objectives to disk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> memoryFile = sgMemory.add(new StringSetting.Builder()
        .name("memory-file")
        .description("Where the bot memory is stored. Leave blank for the default location.")
        .defaultValue("")
        .build()
    );

    // Feedback

    private final Setting<Verbosity> verbosity = sgFeedback.add(new EnumSetting.Builder<Verbosity>()
        .name("verbosity")
        .description("How much the bot reports in chat.")
        .defaultValue(Verbosity.NORMAL)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgFeedback.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print bot status changes to chat.")
        .defaultValue(true)
        .build()
    );

    // HUD

    private final Setting<Boolean> hudEnabled = sgHud.add(new BoolSetting.Builder()
        .name("hud")
        .description("Show the bot status HUD.")
        .defaultValue(false)
        .onChanged(value -> {
            if (value) BotStatusHud.enable();
            else BotStatusHud.disable();
        })
        .build()
    );

    // Runtime

    private final MinecraftWorldPort worldPort = new MinecraftWorldPort();
    private final MinecraftPlayerPort playerPort = new MinecraftPlayerPort();
    private final MinecraftInventoryPort inventoryPort = new MinecraftInventoryPort();
    private final MinecraftNavPort navPort = new MinecraftNavPort();
    private final MinecraftActionPort actionPort = new MinecraftActionPort();
    private final MinecraftItemKnowledge itemKnowledge = new MinecraftItemKnowledge();
    private final MinecraftRecipeSource recipeSource = new MinecraftRecipeSource();
    private final MinecraftBuildPort buildPort = new MinecraftBuildPort(this::schematicController);

    private final BotController controller =
        new BotController(StandardExecutors.create(), new ChatListener(), BotClock.system());

    private MemoryStore memoryStore;
    private BotMemory memory = new BotMemory();
    private boolean recipesIndexed;

    public BotModule() {
        super(LogAddon.CATEGORY, "auto-bot", "Autonomous task bot: gather, craft, build, farm and more.");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onActivate() {
        // Meteor subscribes the module itself (autoSubscribe), so no manual subscription here.
        reloadMemory();
        applySettings();
        controller.setContext(buildContext());
        controller.setAutoStart(autoStart.get());
        if (!controller.state().isActive()) controller.start();
        if (hudEnabled.get()) BotStatusHud.enable();
        info("Bot enabled.");
    }

    @Override
    public void onDeactivate() {
        controller.disable();
        actionPort.cancel();
        navPort.stop();
        saveMemory();
        BotStatusHud.disable();
        info("Bot disabled.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        applySettings();
        if (!recipesIndexed) {
            recipeSource.rebuild();
            recipesIndexed = !recipeSource.isEmpty();
        }
        controller.tick();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        // A new world means a fresh recipe table and a fresh registry cache.
        itemKnowledge.clearCache();
        recipeSource.rebuild();
        recipesIndexed = !recipeSource.isEmpty();
        controller.setContext(buildContext());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        controller.onWorldChanged();
    }

    // ------------------------------------------------------------------ wiring

    private void applySettings() {
        BotConfig config = BotConfig.builder()
            .maxScanResults(scanResults.get())
            .gatherRadius(gatherRadius.get())
            .containerRadius(containerRadius.get())
            .navigationTolerance(navigationTolerance.get())
            .maxTravelDistance(maxTravelDistance.get())
            .minToolDurability(minToolDurability.get())
            .healthThreshold((float) (double) healthThreshold.get())
            .foodThreshold(foodThreshold.get())
            .actionCooldownMs(actionCooldown.get())
            .taskTimeoutMs(taskTimeout.get() * 1000L)
            .maxTasksPerTick(maxTasksPerTick.get())
            .lowPerformanceMode(lowPerformanceMode.get())
            .retryLimit(retryLimit.get())
            .stopOnDisconnect(stopOnDisconnect.get())
            .stopOnLowHealth(stopOnLowHealth.get())
            .build();
        controller.setConfig(config);
        controller.setAutoStart(autoStart.get());
        worldPort.setMaxResults(config.maxScanResults());
        navPort.setMaxDistance(config.maxTravelDistance());
    }

    private TaskContext buildContext() {
        return new TaskContext(worldPort, playerPort, inventoryPort, navPort, actionPort,
            itemKnowledge, recipeSource, buildPort, BotClock.system(), controller.config(), memory);
    }

    private SchematicBuildController schematicController() {
        SchematicDropperModule module = Modules.get().get(SchematicDropperModule.class);
        return module == null ? null : module.buildController();
    }

    private void reloadMemory() {
        memoryStore = persistMemory.get() ? new FileMemoryStore(resolveMemoryPath()) : MemoryStore.inMemory();
        memoryStore.load().ifPresent(loaded -> this.memory = loaded);
    }

    private void saveMemory() {
        if (memoryStore != null) memoryStore.save(memory);
    }

    private Path resolveMemoryPath() {
        String custom = memoryFile.get();
        if (custom != null && !custom.isBlank()) return Path.of(custom.trim());
        return FileMemoryStore.defaultPath();
    }

    // ------------------------------------------------------------------ public API

    public BotController controller() {
        return controller;
    }

    public BotMemory memory() {
        return memory;
    }

    public MinecraftNavPort navigation() {
        return navPort;
    }

    /** Adds a user objective and returns it. */
    public GoalManager.Goal addGoal(String description, String type, String itemId, int amount) {
        GoalManager.Goal goal = controller.addGoal(description, type, itemId, amount);
        controller.setContext(buildContext());
        return goal;
    }

    /** Adds a ready-made task. */
    public boolean addTask(BotTask task) {
        return controller.addTask(task);
    }

    public boolean startBot() {
        return controller.start();
    }

    public boolean pauseBot() {
        return controller.pause("user");
    }

    public boolean resumeBot() {
        return controller.resume();
    }

    public boolean stopBot() {
        return controller.stop("user");
    }

    // ------------------------------------------------------------------ widget

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WHorizontalList row1 = list.add(theme.horizontalList()).expandX().widget();
        WButton start = row1.add(theme.button("Start")).expandX().widget();
        start.action = this::startBot;
        WButton pause = row1.add(theme.button("Pause")).expandX().widget();
        pause.action = this::pauseBot;
        WButton resume = row1.add(theme.button("Resume")).expandX().widget();
        resume.action = this::resumeBot;
        WButton stop = row1.add(theme.button("Stop")).expandX().widget();
        stop.action = this::stopBot;

        WButton dashboard = list.add(theme.button("Bot Dashboard...")).expandX().widget();
        dashboard.action = () -> mc.setScreen(new BotDashboardScreen(this));

        WButton tasks = list.add(theme.button("Task Manager...")).expandX().widget();
        tasks.action = () -> mc.setScreen(new TaskManagerScreen(this));

        WButton save = list.add(theme.button("Save Memory")).expandX().widget();
        save.action = this::saveMemory;

        return list;
    }

    // ------------------------------------------------------------------ feedback

    /** Reports controller events to chat according to the verbosity setting. */
    private final class ChatListener implements BotListener {
        @Override
        public void onStateChanged(BotState state, String reason) {
            if (!chatFeedback.get()) return;
            switch (state) {
                case COMPLETED -> info("Objective complete.");
                case FAILED -> error("Bot stopped: %s",
                    reason == null || reason.isBlank() ? "unknown reason" : reason);
                case PAUSED -> warning("Bot paused: %s",
                    reason == null || reason.isBlank() ? "user" : reason);
                default -> {
                    if (verbosity.get() == Verbosity.VERBOSE) info("State: %s", state);
                }
            }
        }

        @Override
        public void onTaskStarted(BotTask task) {
            if (!chatFeedback.get() || verbosity.get() == Verbosity.SILENT) return;
            info("Working on: %s", task.describe());
        }

        @Override
        public void onTaskFinished(BotTask task) {
            if (!chatFeedback.get() || verbosity.get() != Verbosity.VERBOSE) return;
            info("Finished: %s (%s)", task.describe(), task.state());
        }

        @Override
        public void onObjectiveCompleted(boolean success, String summary) {
            if (!chatFeedback.get()) return;
            if (success) info("Done: %s", summary);
            else warning("Could not complete: %s", summary);
        }

        @Override
        public void onWarning(String message) {
            if (!chatFeedback.get() || verbosity.get() == Verbosity.SILENT) return;
            BotModule.this.warning("%s", message);
        }

        @Override
        public void onFailure(String message) {
            if (!chatFeedback.get()) return;
            BotModule.this.error("%s", message);
        }

        @Override
        public void onFeedback(String message) {
            if (!chatFeedback.get() || verbosity.get() != Verbosity.VERBOSE) return;
            info("%s", message);
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /** A one-line status for the HUD and the dashboard header. */
    public String statusLine() {
        BotState state = controller.state();
        BotTask active = controller.activeTask();
        if (active != null) return state + ": " + active.describe();
        return state + " (" + controller.queue().size() + " queued)";
    }
}
