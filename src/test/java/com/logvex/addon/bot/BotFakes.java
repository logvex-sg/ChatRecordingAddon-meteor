package com.logvex.addon.bot;

import com.logvex.addon.bot.core.ActionPorts;
import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.bot.core.BotClock;
import com.logvex.addon.bot.core.BotConfig;
import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.bot.core.BuildPort;
import com.logvex.addon.bot.core.InventoryPort;
import com.logvex.addon.bot.core.InventorySnapshot;
import com.logvex.addon.bot.core.ItemKnowledge;
import com.logvex.addon.bot.core.ItemStackView;
import com.logvex.addon.bot.core.NavPort;
import com.logvex.addon.bot.core.PlayerPort;
import com.logvex.addon.bot.core.RecipeSource;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.WorldPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory doubles for every bot port, plus a controllable clock.
 *
 * <p>They model the two behaviours that matter for correctness: a world read reflects only what a
 * test explicitly put there, and time only moves when the test moves it. No Minecraft class is
 * touched, so the core can be exercised as ordinary logic.
 */
public final class BotFakes {
    private BotFakes() {
    }

    /** A clock the test advances by hand. */
    public static final class FakeClock implements BotClock {
        private long now;

        public FakeClock(long start) {
            this.now = start;
        }

        @Override
        public long now() {
            return now;
        }

        public void advance(long millis) {
            now += millis;
        }

        public void set(long value) {
            now = value;
        }
    }

    /** A world with no blocks unless a test adds them. */
    public static final class FakeWorld implements WorldPort {
        public boolean canRun = true;
        public String dimension = "minecraft:overworld";
        public BlockPos3 playerPos = new BlockPos3(0, 64, 0);
        public final Map<String, List<BlockPos3>> blocks = new LinkedHashMap<>();
        public final List<BlockPos3> containers = new ArrayList<>();
        public final List<String> entities = new ArrayList<>();
        public final List<String> unloaded = new ArrayList<>();

        @Override
        public boolean canRun() {
            return canRun;
        }

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public BlockPos3 playerPos() {
            return playerPos;
        }

        @Override
        public String blockAt(BlockPos3 pos) {
            for (Map.Entry<String, List<BlockPos3>> entry : blocks.entrySet()) {
                if (entry.getValue().contains(pos)) return entry.getKey();
            }
            return "minecraft:air";
        }

        @Override
        public boolean isLoaded(BlockPos3 pos) {
            return !unloaded.contains(pos);
        }

        @Override
        public List<BlockPos3> findBlocks(List<String> blockIds, int radius) {
            List<BlockPos3> out = new ArrayList<>();
            for (String id : blockIds) {
                List<BlockPos3> found = blocks.get(id);
                if (found != null) out.addAll(found);
            }
            return out;
        }

        @Override
        public List<BlockPos3> findContainers(int radius) {
            return new ArrayList<>(containers);
        }

        @Override
        public List<String> nearbyEntityTypes(int radius) {
            return new ArrayList<>(entities);
        }

        @Override
        public boolean isReplaceable(BlockPos3 pos) {
            return "minecraft:air".equals(blockAt(pos));
        }

        @Override
        public int lightAt(BlockPos3 pos) {
            return 15;
        }
    }

    /** A healthy, creative player standing at the origin. */
    public static final class FakePlayer implements PlayerPort {
        public boolean creative = true;
        public boolean survival = false;
        public float health = 20;
        public int food = 20;
        public boolean screenOpen = false;
        public boolean alive = true;

        @Override
        public boolean isCreative() {
            return creative;
        }

        @Override
        public boolean isSurvival() {
            return survival;
        }

        @Override
        public float health() {
            return health;
        }

        @Override
        public float maxHealth() {
            return 20;
        }

        @Override
        public int foodLevel() {
            return food;
        }

        @Override
        public boolean screenOpen() {
            return screenOpen;
        }

        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public double[] position() {
            return new double[]{0, 64, 0};
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public boolean alive() {
            return alive;
        }
    }

    /** An inventory backed by a simple id-to-count map. */
    public static final class FakeInventory implements InventoryPort {
        public final Map<String, Integer> items = new LinkedHashMap<>();
        public int selectedSlot;

        @Override
        public InventorySnapshot snapshot() {
            Map<Integer, ItemStackView> slots = new LinkedHashMap<>();
            int index = 0;
            for (Map.Entry<String, Integer> entry : items.entrySet()) {
                if (entry.getValue() <= 0) continue;
                slots.put(index++, new ItemStackView(entry.getKey(), entry.getValue(), 64, 0, 0));
            }
            for (int i = index; i < 36; i++) slots.put(i, ItemStackView.blank());
            return new InventorySnapshot(slots, selectedSlot);
        }

        @Override
        public int stackSize(String itemId) {
            return 64;
        }

        @Override
        public boolean knownItem(String itemId) {
            return true;
        }

        @Override
        public boolean selectHotbar(int slot) {
            selectedSlot = slot;
            return true;
        }

        @Override
        public boolean moveToHotbar(String itemId, int amount) {
            return items.containsKey(itemId);
        }

        @Override
        public boolean dropHeld() {
            return true;
        }

        @Override
        public boolean swapWithSlot(int inventorySlot) {
            return true;
        }
    }

    /** A navigator that instantly "arrives" when told to, so tests do not depend on movement. */
    public static final class FakeNav implements NavPort {
        public boolean running;
        public BlockPos3 target;
        public boolean arrivedNow;
        public boolean unreachable;
        public String failure = "";
        public double maxDistance = 256;
        public int ticks;

        @Override
        public double[] position() {
            return new double[]{0, 64, 0};
        }

        @Override
        public boolean start(BlockPos3 target) {
            this.target = target;
            this.running = true;
            return true;
        }

        @Override
        public void tick(long nowMs) {
            ticks++;
            if (arrivedNow) running = false;
        }

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean arrived(BlockPos3 target, double tolerance) {
            return arrivedNow;
        }

        @Override
        public String failureReason() {
            return failure;
        }

        @Override
        public boolean unreachable() {
            return unreachable;
        }

        @Override
        public double maxDistance() {
            return maxDistance;
        }
    }

    /** Records every action so a test can assert what the bot tried to do. */
    public static final class FakeActions implements ActionPorts {
        public final List<String> calls = new ArrayList<>();
        public boolean canAct = true;
        public boolean mineResult = true;

        @Override
        public boolean mine(BlockPos3 pos) {
            calls.add("mine:" + pos);
            return mineResult;
        }

        @Override
        public boolean place(BlockPos3 pos, String itemId) {
            calls.add("place:" + pos + ":" + itemId);
            return true;
        }

        @Override
        public boolean interact(BlockPos3 pos) {
            calls.add("interact:" + pos);
            return true;
        }

        @Override
        public boolean useItem() {
            calls.add("use");
            return true;
        }

        @Override
        public boolean craftingReady() {
            return false;
        }

        @Override
        public boolean craft(String recipeId, int times) {
            calls.add("craft:" + recipeId);
            return true;
        }

        @Override
        public boolean furnaceReady() {
            return false;
        }

        @Override
        public boolean smelt(String inputId, String fuelId, int count) {
            calls.add("smelt:" + inputId);
            return true;
        }

        @Override
        public boolean canAct() {
            return canAct;
        }

        @Override
        public void cancel() {
            calls.add("cancel");
        }
    }

    /** Fixed registry knowledge for planning tests. */
    public static final class FakeKnowledge implements ItemKnowledge {
        public final Map<String, List<String>> tags = new LinkedHashMap<>();
        public final Map<String, List<String>> yields = new LinkedHashMap<>();

        @Override
        public List<String> itemsInTag(String tag) {
            return tags.getOrDefault(tag, List.of());
        }

        @Override
        public List<String> toolsFor(String blockId) {
            return List.of(blockId);
        }

        @Override
        public boolean isLog(String itemId) {
            return itemId != null && itemId.endsWith("_log");
        }

        @Override
        public boolean isContainer(String itemId) {
            return itemId != null && itemId.endsWith("_chest");
        }

        @Override
        public boolean isSeed(String itemId) {
            return itemId != null && itemId.endsWith("_seeds");
        }

        @Override
        public List<String> blocksYielding(String itemId) {
            return yields.getOrDefault(itemId, List.of(itemId));
        }
    }

    /** Fixed recipe table for planning tests. */
    public static final class FakeRecipes implements RecipeSource {
        public final Map<String, List<Recipe>> byOutput = new LinkedHashMap<>();

        public FakeRecipes add(String output, int outputCount, Ingredient... ingredients) {
            byOutput.computeIfAbsent(output, k -> new ArrayList<>())
                .add(new Recipe("test:" + output, output, outputCount, List.of(ingredients)));
            return this;
        }

        @Override
        public List<Recipe> recipesFor(String itemId) {
            return byOutput.getOrDefault(itemId, List.of());
        }
    }

    /** A build port that does nothing unless a test drives it. */
    public static final class FakeBuild implements BuildPort {
        public boolean hasSchematic = true;
        public boolean running;
        public double progress;
        public boolean startResult = true;

        @Override
        public boolean hasSchematic() {
            return hasSchematic;
        }

        @Override
        public String schematicName() {
            return "test";
        }

        @Override
        public boolean startBuild() {
            running = startResult;
            return startResult;
        }

        @Override
        public void stopBuild() {
            running = false;
        }

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public double progress() {
            return progress;
        }

        @Override
        public String status() {
            return running ? "running" : "idle";
        }

        @Override
        public String lastError() {
            return "";
        }
    }

    /** A context wiring all the fakes together. */
    public static final class Harness {
        public final FakeWorld world = new FakeWorld();
        public final FakePlayer player = new FakePlayer();
        public final FakeInventory inventory = new FakeInventory();
        public final FakeNav nav = new FakeNav();
        public final FakeActions actions = new FakeActions();
        public final FakeKnowledge knowledge = new FakeKnowledge();
        public final FakeRecipes recipes = new FakeRecipes();
        public final FakeBuild build = new FakeBuild();
        public final FakeClock clock = new FakeClock(1_000_000);
        public final BotMemory memory = new BotMemory();
        public BotConfig config = BotConfig.DEFAULTS;

        public TaskContext context() {
            return new TaskContext(world, player, inventory, nav, actions, knowledge, recipes, build,
                clock, config, memory);
        }
    }
}
