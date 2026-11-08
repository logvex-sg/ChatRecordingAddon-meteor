package com.logvex.addon.world.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.settings.StringSetting;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.world.core.LocationCategory;
import com.logvex.addon.world.gui.LocationDatabaseScreen;
import com.logvex.addon.world.gui.WorldMapScreen;
import com.logvex.addon.world.mc.WorldService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records what the client discovers about the world it is in.
 *
 * <p>The module drives {@link WorldService}: how often to sample, how far to scan, which blocks count
 * as resources and which block signatures indicate a structure. All the decision logic lives in the
 * Minecraft-free world core; this is only the settings and the tick that calls the service. The
 * service is subscribed once at bootstrap, so switching worlds and persistence work even while this
 * module is off; the module only controls the active scanning.
 */
public class WorldIntelligenceModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Scanning");
    private final SettingGroup sgWatch = settings.createGroup("Watch List");

    private final Setting<Boolean> trackChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("track-chunks").description("Record the chunks you visit, so the map can show where you have been.")
        .defaultValue(true).build());

    private final Setting<Boolean> trackBiomes = sgGeneral.add(new BoolSetting.Builder()
        .name("track-biomes").description("Record each biome you stand in and count how often you see it.")
        .defaultValue(true).build());

    private final Setting<Boolean> markSpawn = sgGeneral.add(new BoolSetting.Builder()
        .name("mark-spawn").description("Mark the world spawn as a waypoint the first time you see it.")
        .defaultValue(true).build());

    private final Setting<Integer> scanInterval = sgScan.add(new IntSetting.Builder()
        .name("scan-interval").description("How often, in ticks, to sample the world. Higher is cheaper.")
        .defaultValue(40).min(5).max(1200).sliderRange(5, 200).build());

    private final Setting<Integer> scanRadius = sgScan.add(new IntSetting.Builder()
        .name("scan-radius").description("How far, in blocks, to scan for watched blocks. 0 disables block scanning.")
        .defaultValue(24).min(0).max(64).sliderRange(0, 64).build());

    private final Setting<Boolean> trackResources = sgScan.add(new BoolSetting.Builder()
        .name("track-resources").description("Record watched resource blocks and structure signatures when seen.")
        .defaultValue(true).build());

    private final Setting<List<String>> resources = sgWatch.add(new StringListSetting.Builder()
        .name("resources").description("Block ids to record as resources, e.g. minecraft:diamond_ore.")
        .defaultValue(
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:ancient_debris", "minecraft:emerald_ore",
            "minecraft:deepslate_emerald_ore", "minecraft:gold_ore",
            "minecraft:deepslate_gold_ore")
        .build());

    private final Setting<String> structureBlocks = sgWatch.add(new StringSetting.Builder()
        .name("structure-blocks").description("Comma-separated block=name pairs that mark a structure, "
            + "e.g. minecraft:chest=Village chest. Recorded as a structure when seen.")
        .defaultValue("")
        .build());

    private long lastScan;
    private boolean spawnMarked;

    public WorldIntelligenceModule() {
        super(LogClient.CATEGORY, "world-intelligence",
            "Remembers discovered locations, biomes, resources and chunks, per world.");
    }

    @Override
    public void onActivate() {
        WorldService.get().setEnabled(true);
        lastScan = 0;
    }

    @Override
    public void onDeactivate() {
        WorldService.get().saveAsync();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        if (now - lastScan < scanInterval.get() * 50L) return;
        lastScan = now;

        List<String> watch = trackResources.get() ? resources.get() : List.of();
        Map<String, String> structures = trackResources.get() ? parseStructures(structureBlocks.get()) : Map.of();
        WorldService.get().sample(scanRadius.get(), watch, structures,
            trackChunks.get(), trackBiomes.get());

        if (markSpawn.get() && !spawnMarked) markSpawnOnce();
    }

    private void markSpawnOnce() {
        try {
            var spawn = mc.world.getSpawnPos();
            if (spawn == null) return;
            spawnMarked = true;
            WorldService.get().intelligence().recordLocation(
                mc.world.getRegistryKey().getValue().toString(),
                spawn.getX(), spawn.getY(), spawn.getZ(), "World Spawn", LocationCategory.WAYPOINT,
                System.currentTimeMillis());
            WorldService.get().saveAsync();
        } catch (RuntimeException e) {
            // Spawn is unavailable in some dimensions; simply skip it.
        }
    }

    /** Parses {@code a=b, c=d} into a map, ignoring malformed pairs. */
    static Map<String, String> parseStructures(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String block = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String name = pair.substring(eq + 1).trim();
            if (!block.isEmpty() && !name.isEmpty()) out.put(block, name);
        }
        return out;
    }

    public WorldService service() {
        return WorldService.get();
    }

    /** Opens the world map, used by the command and the GUI button. */
    public void openMap() {
        if (mc != null) mc.setScreen(new WorldMapScreen(null));
    }

    /** Opens the location database. */
    public void openDatabase() {
        if (mc != null) mc.setScreen(new LocationDatabaseScreen(null));
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = new WVerticalList();
        list.theme = theme;
        list.add(new WButton("Open World Map", this::openMap));
        list.add(new WButton("Open Location Database", this::openDatabase));
        return list;
    }
}
