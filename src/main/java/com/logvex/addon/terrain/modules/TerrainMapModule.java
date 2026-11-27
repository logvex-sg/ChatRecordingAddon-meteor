package com.logvex.addon.terrain.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.framework.systems.modules.AddonModule;
import com.logvex.addon.terrain.core.TerrainStore;
import com.logvex.addon.terrain.mc.TerrainService;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.orbit.EventHandler;

/**
 * Captures the surface of loaded chunks so the companion map has real terrain to draw.
 *
 * <p>The module is only settings and a tick: it decides how far to look, how many chunks to read per
 * pass and how many to keep, and {@link TerrainService} does the reading. Capture is off by default
 * for the same reason the world database is opt-in — reading thousands of chunks is work the user
 * should choose — and it is bounded so turning it on cannot stall a tick.
 *
 * <p>Nothing here writes to the world or asks the server for anything the client was not already
 * sent; it reads the chunks Minecraft has loaded around the player.
 */
public class TerrainMapModule extends AddonModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStorage = settings.createGroup("Storage");

    private final Setting<Integer> captureInterval = sgGeneral.add(new IntSetting.Builder()
        .name("capture-interval")
        .description("How often, in ticks, to capture terrain. 20 ticks is about once a second.")
        .defaultValue(20).min(1).max(1200).sliderRange(5, 200).build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("How far from the player, in chunks, to capture. 8 is a 128-block square around you.")
        .defaultValue(8).min(1).max(32).sliderRange(1, 32).build());

    private final Setting<Integer> budget = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-pass")
        .description("The most chunks to read in one pass. Lower is smoother; higher fills the map faster.")
        .defaultValue(8).min(1).max(64).sliderRange(1, 64).build());

    private final Setting<Integer> capacity = sgStorage.add(new IntSetting.Builder()
        .name("max-chunks")
        .description("How many chunks to keep in total. The oldest observation is dropped first.")
        .defaultValue(TerrainStore.DEFAULT_CAPACITY)
        .min(100).max(500_000).sliderRange(1_000, 100_000)
        .onChanged(value -> TerrainService.get().world().store().setCapacity(value))
        .build());

    private long lastCapture;

    public TerrainMapModule() {
        super(LogClient.CATEGORY, "terrain-map",
            "Captures the surface of loaded chunks for the companion's interactive map.");
    }

    @Override
    public void onActivate() {
        TerrainService.get().setEnabled(true);
        TerrainService.get().world().store().setCapacity(capacity.get());
        lastCapture = 0;
    }

    @Override
    public void onDeactivate() {
        TerrainService.get().saveAsync();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        SafeEventHandler.guard("TerrainMapModule.onTick", () -> {
            if (mc.player == null || mc.world == null) return;
            long now = System.currentTimeMillis();
            if (now - lastCapture < captureInterval.get() * 50L) return;
            lastCapture = now;
            TerrainService.get().capture(radius.get(), budget.get());
        }).run();
    }

    public TerrainService service() {
        return TerrainService.get();
    }
}
