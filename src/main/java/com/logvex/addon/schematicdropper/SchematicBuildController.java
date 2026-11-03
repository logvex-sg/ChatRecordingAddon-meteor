package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BuildStatistics;
import com.logvex.addon.schematicdropper.core.BuildTask;
import com.logvex.addon.schematicdropper.core.BuildZoneManager;
import com.logvex.addon.schematicdropper.core.Checkpoint;
import com.logvex.addon.schematicdropper.core.CheckpointStore;
import com.logvex.addon.schematicdropper.core.ConflictPolicy;
import com.logvex.addon.schematicdropper.core.DependencySolver;
import com.logvex.addon.schematicdropper.core.FailureLog;
import com.logvex.addon.schematicdropper.core.PlacementConfig;
import com.logvex.addon.schematicdropper.core.PlacementEngine;
import com.logvex.addon.schematicdropper.core.PlacementFailure;
import com.logvex.addon.schematicdropper.core.PlacementListener;
import com.logvex.addon.schematicdropper.core.PlacementSnapshot;
import com.logvex.addon.schematicdropper.core.PlacementState;
import com.logvex.addon.schematicdropper.core.PlacementStateMachine;
import com.logvex.addon.schematicdropper.core.PlannedBlock;
import com.logvex.addon.schematicdropper.core.RecoveryManager;
import com.logvex.addon.schematicdropper.core.Schematic;
import com.logvex.addon.schematicdropper.core.SchematicParseException;
import com.logvex.addon.schematicdropper.core.TaskQueue;
import com.logvex.addon.schematicdropper.core.WorldComparison;
import com.logvex.addon.schematicdropper.mc.FileCheckpointStore;
import com.logvex.addon.schematicdropper.mc.MinecraftBlockTraits;
import com.logvex.addon.schematicdropper.mc.MinecraftPlacementAction;
import com.logvex.addon.schematicdropper.mc.MinecraftPlacementEnv;
import com.logvex.addon.schematicdropper.mc.SchematicFileLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the schematic build pipeline: load a file, compare it with the world, order the work,
 * place and verify each block, checkpoint progress and recover after a restart.
 *
 * <p>The controller is deliberately small: all decisions live in the Minecraft-free core, and the
 * Minecraft adapters only read the world and send interactions. File reads happen on a background
 * worker and their result is applied on the client tick, so loading a large schematic never blocks
 * the game thread.
 */
public final class SchematicBuildController {
    /** Chat sink, implemented by the module so output formatting stays in one place. */
    public interface Feedback {
        void info(String message);

        void warn(String message);

        void error(String message);

        void debug(String message);
    }

    private final Feedback feedback;
    private final MinecraftPlacementEnv env = new MinecraftPlacementEnv();
    private final MinecraftPlacementAction action = new MinecraftPlacementAction(env);
    private final MinecraftBlockTraits traits = new MinecraftBlockTraits();
    private final PlacementEngine engine;
    private final CheckpointStore checkpoints;
    private final RecoveryManager recovery;
    private final TaskQueue queue = new TaskQueue();
    private final BuildStatistics statistics = new BuildStatistics();
    private final FailureLog failures = new FailureLog();
    private final BuildZoneManager zones = new BuildZoneManager();
    private ExecutorService loader = newLoader();

    private volatile LoadedFile pending;
    private volatile String loadError;
    private volatile boolean loading;

    private Schematic schematic;
    private BlockPos3 origin = new BlockPos3(0, 0, 0);
    private PlacementConfig config = PlacementConfig.defaults();
    private String activeName;
    private int loadGeneration;
    private long startedAt;
    private int lastCompleted;
    private int lastFailed;
    private boolean sessionRecorded;

    public SchematicBuildController(Feedback feedback, Path checkpointDirectory) {
        this.feedback = feedback;
        this.engine = new PlacementEngine(env, action, new EngineListener());
        this.engine.setTraits(traits);
        this.checkpoints = new FileCheckpointStore(checkpointDirectory);
        this.recovery = new RecoveryManager(checkpoints);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Re-arms the engine after the module is enabled. */
    public void activate() {
        if (loader.isShutdown()) loader = newLoader();
        engine.onWorldChanged();
    }

    private static ExecutorService newLoader() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "logvex-schematic-loader");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Stops all work and releases the loader thread. */
    public void deactivate() {
        engine.stop();
        loader.shutdownNow();
    }

    /** Called once per client tick while the module is enabled. */
    public void tick(long nowMs) {
        applyPendingLoad();
        if (!engine.state().isRunning()) return;

        engine.setConfig(config);
        engine.tick(nowMs);

        PlacementSnapshot snapshot = engine.snapshot();
        if (snapshot.completed() != lastCompleted || snapshot.failed() != lastFailed) {
            lastCompleted = snapshot.completed();
            lastFailed = snapshot.failed();
            checkpoint(nowMs);
        }
        if (engine.state() == PlacementStateMachine.COMPLETED) recordSession(nowMs, true);
    }

    /** The world changed: stop and clear the run so nothing stale is placed. */
    public void onWorldChanged() {
        if (engine.state().isRunning()) {
            recordSession(System.currentTimeMillis(), false);
        }
        engine.onWorldChanged();
        action.cancelPending();
    }

    // ------------------------------------------------------------------ loading

    /** Loads a schematic file on a background worker. The result is applied on the next tick. */
    public void loadAsync(Path path) {
        if (path == null) return;
        if (loader.isShutdown()) {
            feedback.error("Enable the module before loading a schematic.");
            return;
        }
        loading = true;
        loadError = null;
        pending = null;
        try {
            loader.submit(() -> {
                try {
                    Schematic loaded = new SchematicFileLoader().load(path);
                    pending = new LoadedFile(loaded, path.getFileName().toString());
                } catch (SchematicParseException e) {
                    loadError = e.getMessage();
                } catch (RuntimeException e) {
                    loadError = "Could not read that file.";
                } finally {
                    loading = false;
                }
            });
        } catch (RuntimeException e) {
            loading = false;
            feedback.error("Could not start loading that file.");
        }
    }

    public boolean isLoading() {
        return loading;
    }

    private void applyPendingLoad() {
        LoadedFile loaded = pending;
        if (loaded != null) {
            pending = null;
            applySchematic(loaded.schematic(), loaded.fileName());
        }
        String error = loadError;
        if (error != null) {
            loadError = null;
            feedback.error(error);
        }
    }

    private void applySchematic(Schematic loaded, String fileName) {
        this.schematic = loaded;
        this.activeName = loaded.name();
        this.loadGeneration++;
        zones.split(loaded, 16);

        if (!loaded.hasGeometry()) {
            feedback.warn(fileName + " contains no blocks.");
            return;
        }
        feedback.info("Loaded " + fileName + ": "
            + com.logvex.addon.common.Messages.plural(loaded.blockCount(), "block")
            + " (" + loaded.sizeX() + "\u00d7" + loaded.sizeY() + "\u00d7" + loaded.sizeZ() + ").");

        RecoveryManager.Plan resume = recovery.planResume(loaded, origin, compare());
        if (resume.canResume()) {
            feedback.info("A checkpoint exists for this schematic: "
                + resume.verifiedComplete() + " of " + loaded.blockCount()
                + " blocks are already in place.");
        }
    }

    public boolean hasSchematic() {
        return schematic != null && schematic.hasGeometry();
    }

    /** Bumped every time a new schematic is applied, so callers can sync derived state once. */
    public int loadGeneration() {
        return loadGeneration;
    }

    public Schematic schematic() {
        return schematic;
    }

    // ------------------------------------------------------------------- build

    /** Sets the origin the schematic is placed at. */
    public void setOrigin(BlockPos3 origin) {
        if (origin != null) this.origin = origin;
    }

    public BlockPos3 origin() {
        return origin;
    }

    public void setConfig(PlacementConfig config) {
        if (config != null) this.config = config;
    }

    /** Compares the schematic with the live world. */
    public WorldComparison compare() {
        WorldSource world = new WorldSource();
        if (schematic == null) {
            return WorldComparison.compare(Schematic.empty("none"), world, origin, traits);
        }
        return WorldComparison.compare(schematic, world, origin, traits);
    }

    /**
     * Starts a build. Offers to resume from a valid checkpoint, otherwise builds the whole
     * schematic in dependency order.
     */
    public boolean startBuild(long nowMs) {
        if (!hasSchematic()) {
            feedback.error("Load a schematic first.");
            return false;
        }
        if (engine.state().isRunning()) {
            feedback.error("A build is already running.");
            return false;
        }

        WorldComparison comparison = compare();
        RecoveryManager.Plan resume = recovery.planResume(schematic, origin, comparison);
        DependencySolver.Plan plan;
        if (resume.canResume() && resume.hasPendingWork()) {
            plan = engine.setComparison(comparison, schematic);
            engine.setPlan(resume.pending());
            feedback.info("Resuming " + activeName + ": " + resume.pending().size() + " blocks to place.");
        } else {
            plan = engine.setComparison(comparison, schematic);
            if (plan.ordered().isEmpty()) {
                feedback.info("Nothing to build: every block is already in place.");
                return false;
            }
            if (plan.unresolvedCount() > 0) {
                feedback.warn(plan.unresolvedCount() + " blocks could not be ordered and will be skipped.");
            }
            feedback.info("Building " + activeName + ": " + plan.ordered().size() + " blocks to place.");
        }

        startedAt = nowMs;
        lastCompleted = 0;
        lastFailed = 0;
        sessionRecorded = false;
        failures.clear();
        engine.start(nowMs);
        queue.enqueue(activeName, schematic, origin, nowMs);
        queue.startNext();
        return true;
    }

    public void pause() {
        engine.pause();
    }

    public void resume(long nowMs) {
        engine.resume(nowMs);
    }

    public void stop(long nowMs) {
        if (engine.state().isRunning()) recordSession(nowMs, false);
        engine.stop();
        if (queue.active() != null) queue.finishActive(BuildTask.Status.CANCELLED);
    }

    public void retryFailed() {
        engine.retryAllFailed();
    }

    public void restartBlock(BlockPos3 pos) {
        engine.retry(pos);
    }

    private void recordSession(long nowMs, boolean completed) {
        if (sessionRecorded) return;
        sessionRecorded = true;

        PlacementSnapshot snapshot = engine.snapshot();
        statistics.recordSession(new BuildStatistics.Session(
            activeName == null ? "build" : activeName,
            startedAt,
            Math.max(0, nowMs - startedAt),
            snapshot.completed(),
            snapshot.failed(),
            snapshot.skipped(),
            snapshot.percent(),
            completed));
    }

    private void checkpoint(long nowMs) {
        if (schematic == null || activeName == null) return;
        try {
            Checkpoint capture = Checkpoint.capture(schematic.name(), Checkpoint.fingerprint(schematic),
                origin, engine, nowMs);
            checkpoints.save(capture);
        } catch (RuntimeException e) {
            // A failed checkpoint must never interrupt a build; it only costs a re-do on restart.
            feedback.debug("Could not save checkpoint: " + e.getMessage());
        }
    }

    /** Saves a checkpoint right now, e.g. from the GUI before closing. */
    public void saveCheckpointNow() {
        checkpoint(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ readers

    public PlacementSnapshot snapshot() {
        return engine.snapshot();
    }

    public PlacementStateMachine state() {
        return engine.state();
    }

    public List<PlannedBlock> plan() {
        return engine.plan();
    }

    public TaskQueue queue() {
        return queue;
    }

    public BuildStatistics statistics() {
        return statistics;
    }

    public FailureLog failures() {
        return failures;
    }

    public BuildZoneManager zones() {
        return zones;
    }

    public List<Checkpoint> storedCheckpoints() {
        return checkpoints.list();
    }

    public void deleteCheckpoint() {
        if (schematic != null) checkpoints.delete(schematic.name());
    }

    // ------------------------------------------------------------------ plumbing

    private record LoadedFile(Schematic schematic, String fileName) {
    }

    /** Bridges the live client world to the Minecraft-free {@code BlockWorld}. */
    private final class WorldSource implements com.logvex.addon.schematicdropper.core.BlockWorld {
        @Override
        public com.logvex.addon.schematicdropper.core.BlockStateSpec stateAt(BlockPos3 pos) {
            return env.stateAt(pos);
        }

        @Override
        public boolean isReplaceable(BlockPos3 pos) {
            return env.isReplaceable(pos);
        }

        @Override
        public boolean isLoaded(BlockPos3 pos) {
            return env.isLoaded(pos);
        }
    }

    /** Routes engine events to chat and to the failure log. */
    private final class EngineListener implements PlacementListener {
        @Override
        public void onStateChanged(PlacementStateMachine state) {
            feedback.debug("Build state: " + state);
        }

        @Override
        public void onPlaced(BlockPos3 pos, com.logvex.addon.schematicdropper.core.BlockStateSpec block) {
            statistics.recordPlacement();
        }

        @Override
        public void onFailed(BlockPos3 pos, PlacementFailure failure, String detail) {
            statistics.recordFailure();
            failures.record(pos, null, failure, detail, System.currentTimeMillis());
        }

        @Override
        public void onConflict(BlockPos3 pos, com.logvex.addon.schematicdropper.core.BlockStateSpec expected,
                               com.logvex.addon.schematicdropper.core.BlockStateSpec found) {
            failures.record(pos, null, PlacementFailure.WORLD_CHANGED,
                "occupied by " + com.logvex.addon.common.Messages.prettyId(found.id()),
                System.currentTimeMillis());
        }

        @Override
        public void onCompleted(long placedCount, long failedCount, long elapsedMs) {
            String message = "Build complete. "
                + com.logvex.addon.common.Messages.plural(placedCount, "block") + " placed";
            if (failedCount > 0) message += ", " + failedCount + " failed";
            message += " in " + com.logvex.addon.common.Messages.duration(elapsedMs) + ".";
            feedback.info(message);
        }

        @Override
        public void onFeedback(String message) {
            feedback.debug(message);
        }
    }
}
