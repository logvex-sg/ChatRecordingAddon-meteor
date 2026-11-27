package com.logvex.addon.telemetry.mc;

import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.telemetry.core.TelemetryRecorder;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.IEventBus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.effect.StatusEffectInstance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Samples real client measurements into the {@link TelemetryRecorder}, at most once a second.
 *
 * <p>Each metric is read from the client itself: frames per second from the render loop, latency from
 * the player's own tab-list entry, health and food from the player, position from the player. A value
 * the client cannot provide right now is skipped, not recorded as zero — that is why the graphs show
 * a gap when the player leaves the world instead of a cliff to nothing.
 *
 * <p>Sampling is on the client thread because every source is client state, but it is trivial: eight
 * field reads once a second. Nothing here allocates a collection per tick.
 */
public final class TelemetryService {
    private static final TelemetryService INSTANCE = new TelemetryService();

    public static TelemetryService get() {
        return INSTANCE;
    }

    /** One sample a second: fine enough to see a spike, coarse enough to keep the ring small. */
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    /** The metric names the app draws. Kept as constants so the sampler and the UI cannot drift. */
    public static final String FPS = "fps";
    public static final String PING = "ping";
    public static final String HEALTH = "health";
    public static final String FOOD = "food";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String Z = "z";
    public static final String ENTITIES = "entities";
    public static final String EFFECTS = "effects";

    private final TelemetryRecorder recorder = new TelemetryRecorder();
    private volatile long lastSampleAt;
    private volatile boolean recording = true;

    private TelemetryService() {
    }

    public TelemetryRecorder recorder() {
        return recorder;
    }

    public void init(IEventBus bus) {
        if (bus != null) bus.subscribe(this);
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        // A new world is a new session; a gap between sessions must not read as a continuous line.
        SafeEventHandler.guard("TelemetryService.onJoin", () -> lastSampleAt = 0L).run();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        SafeEventHandler.guard("TelemetryService.onTick", this::sampleIfDue).run();
    }

    /** Samples when the interval has elapsed; safe to call every tick. */
    void sampleIfDue() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return;
        lastSampleAt = now;

        MinecraftClient mc = LogClientContext.mc;
        if (mc == null) return;

        // Frames per second is meaningful even on the title screen, where there is no world.
        recorder.record(FPS, now, mc.getCurrentFps());

        if (mc.player == null || mc.world == null) return;

        int ping = ping(mc);
        if (ping >= 0) recorder.record(PING, now, ping);

        recorder.record(HEALTH, now, mc.player.getHealth());
        recorder.record(FOOD, now, mc.player.getHungerManager().getFoodLevel());
        recorder.record(X, now, mc.player.getX());
        recorder.record(Y, now, mc.player.getY());
        recorder.record(Z, now, mc.player.getZ());
        recorder.record(ENTITIES, now, mc.world.getRegularEntityCount());
        recorder.record(EFFECTS, now, effectCount(mc));
    }

    /** The player's latency in milliseconds, or -1 when the server has not reported one. */
    private static int ping(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null || mc.player == null) return -1;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? -1 : entry.getLatency();
    }

    private static int effectCount(MinecraftClient mc) {
        int count = 0;
        for (StatusEffectInstance ignored : mc.player.getStatusEffects()) count++;
        return count;
    }

    public boolean isRecording() {
        return recording;
    }

    public void setRecording(boolean value) {
        this.recording = value;
    }

    /** A snapshot of every series, for the API. Maps metric name to its samples and statistics. */
    public Map<String, Object> seriesForApi() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String metric : recorder.metrics()) {
            TelemetryRecorder.Series series = recorder.series(metric);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamps", series.timestamps());
            entry.put("values", series.values());
            entry.put("latest", number(series.latest()));
            entry.put("min", number(series.min()));
            entry.put("max", number(series.max()));
            entry.put("average", number(series.average()));
            out.put(metric, entry);
        }
        return out;
    }

    /** NaN cannot be represented in JSON, so an absent statistic is sent as null. */
    private static Object number(double value) {
        return Double.isNaN(value) ? null : value;
    }

    /** The CSV export of every series, for a spreadsheet or an external dashboard. */
    public String toCsv() {
        StringBuilder out = new StringBuilder("metric,timestamp,value\n");
        for (String metric : recorder.metrics()) {
            TelemetryRecorder.Series series = recorder.series(metric);
            var times = series.timestamps();
            var values = series.values();
            for (int i = 0; i < times.size(); i++) {
                out.append(metric).append(',').append(times.get(i)).append(',')
                    .append(values.get(i)).append('\n');
            }
        }
        return out.toString();
    }
}
