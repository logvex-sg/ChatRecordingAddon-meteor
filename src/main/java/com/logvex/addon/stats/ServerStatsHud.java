package com.logvex.addon.stats;

import com.logvex.addon.LogClient;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD panel showing the server's tick rate, latency and the local player's state.
 *
 * <p>Each row can be switched off individually and the panel re-measures itself when the list
 * changes, so a user can keep it to a single TPS line or expand it to the full set. The figures come
 * from {@link McServerStats}, the same source the chat commands read, so the HUD and {@code !tps}
 * always agree.
 */
public class ServerStatsHud extends HudElement {
    public static final HudElementInfo<ServerStatsHud> INFO = new HudElementInfo<>(
        LogClient.HUD_GROUP, "server-stats", "Live server TPS, ping and player state.", ServerStatsHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showTps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-tps").description("Show the server's tick rate.").defaultValue(true).build());
    private final Setting<Boolean> showPing = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ping").description("Show the round-trip latency.").defaultValue(true).build());
    private final Setting<Boolean> showPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-players").description("Show the visible player count.").defaultValue(true).build());
    private final Setting<Boolean> showUptime = sgGeneral.add(new BoolSetting.Builder()
        .name("show-uptime").description("Show how long the session has been open.").defaultValue(false).build());
    private final Setting<Boolean> showCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("show-coords").description("Show your position.").defaultValue(false).build());
    private final Setting<Boolean> showHealth = sgGeneral.add(new BoolSetting.Builder()
        .name("show-health").description("Show health and hunger.").defaultValue(false).build());
    private final Setting<Boolean> showWorldTime = sgGeneral.add(new BoolSetting.Builder()
        .name("show-world-time").description("Show the in-game clock.").defaultValue(false).build());
    private final Setting<Boolean> showFps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-fps").description("Show the client's frame rate.").defaultValue(false).build());

    private final Setting<Integer> sampleSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("sample-seconds")
        .description("How often the figures are re-read. Zero re-reads every frame.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private ServerStats cached = ServerStats.OFFLINE;
    private long sampledAt;
    private long sessionStart = System.currentTimeMillis();

    public ServerStatsHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        List<Row> rows = rows(snapshot());

        double width = renderer.textWidth("Server", shadow, scale);
        for (Row row : rows) {
            double rowWidth = renderer.textWidth(row.key(), shadow, scale)
                + renderer.textWidth(row.value(), shadow, scale) + 10;
            width = Math.max(width, rowWidth);
        }
        double lineHeight = renderer.textHeight(shadow, scale) + HudPanel.LINE_GAP;
        int count = Math.max(1, rows.size());
        setSize(width + HudPanel.PAD * 2, lineHeight * (count + 1) + HudPanel.PAD * 2);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Server", scale, shadow);

        List<Row> rows = rows(snapshot());
        if (rows.isEmpty()) {
            HudPanel.line(renderer, this, "No figures selected", cursor, style.muted(), scale, shadow);
            return;
        }
        for (Row row : rows) {
            HudPanel.keyValue(renderer, this, row.key(), row.value(), cursor, style.muted(), style.text(),
                scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }

    private record Row(String key, String value) {
    }

    /** The current figures, re-read at most every {@code sample-seconds}. */
    private ServerStats snapshot() {
        int period = Math.max(0, sampleSeconds.get());
        long now = System.currentTimeMillis();
        if (period > 0 && now - sampledAt < period * 1000L) return cached;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            cached = ServerStats.OFFLINE;
            sampledAt = now;
            return cached;
        }
        if (cached == ServerStats.OFFLINE) sessionStart = now;
        cached = McServerStats.snapshot(mc, now, sessionStart);
        sampledAt = now;
        return cached;
    }

    private List<Row> rows(ServerStats stats) {
        List<Row> out = new ArrayList<>(8);
        if (!stats.inWorld()) {
            out.add(new Row("Status", "not connected"));
            return out;
        }
        if (showTps.get()) {
            out.add(new Row("TPS", ServerStats.formatTps(stats.tps())
                + (stats.lagging() ? " (lagging)" : "")));
        }
        if (showPing.get()) {
            out.add(new Row("Ping", stats.latency() < 0 ? "n/a" : stats.latency() + " ms"));
        }
        if (showPlayers.get()) out.add(new Row("Players", String.valueOf(stats.players())));
        if (showUptime.get()) out.add(new Row("Uptime", ServerStats.formatUptime(stats.uptimeMs())));
        if (showCoords.get()) {
            out.add(new Row("XYZ", String.format(java.util.Locale.ROOT, "%.0f %.0f %.0f",
                stats.x(), stats.y(), stats.z())));
        }
        if (showHealth.get()) {
            out.add(new Row("Health", String.format(java.util.Locale.ROOT, "%.1f/%.0f",
                stats.health(), stats.maxHealth())));
            out.add(new Row("Food", String.valueOf(stats.food())));
        }
        if (showWorldTime.get()) out.add(new Row("Time", ServerStats.formatWorldTime(stats.worldTime())));
        if (showFps.get()) out.add(new Row("FPS", String.valueOf(stats.fps())));
        return out;
    }
}
