package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudSettings;
import com.logvex.addon.framework.systems.hud.HudStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD summarising the current session statistics.
 *
 * <p>Each row is a key/value pair, right-aligned, so the panel reads as a small table rather than a
 * ragged list. Values use the shared formatters, so "4.2k" and "3m 12s" look the same here as in chat.
 */
public class StatsHud extends HudElement {
    public static final HudElementInfo<StatsHud> INFO =
        new HudElementInfo<>(LogClient.HUD_GROUP, "session-stats", "Session chat statistics.", StatsHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showTotal = sgGeneral.add(new BoolSetting.Builder()
        .name("show-total").description("Show the total message count.").defaultValue(true).build());
    private final Setting<Boolean> showRate = sgGeneral.add(new BoolSetting.Builder()
        .name("show-rate").description("Show messages per minute.").defaultValue(true).build());
    private final Setting<Boolean> showPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-players").description("Show the unique player count.").defaultValue(true).build());
    private final Setting<Boolean> showJoins = sgGeneral.add(new BoolSetting.Builder()
        .name("show-joins").description("Show the join/leave totals.").defaultValue(true).build());
    private final Setting<Boolean> showDeaths = sgGeneral.add(new BoolSetting.Builder()
        .name("show-deaths").description("Show the death total.").defaultValue(false).build());
    private final Setting<Boolean> showKeywords = sgGeneral.add(new BoolSetting.Builder()
        .name("show-keyword-alerts").description("Show the keyword alert total.").defaultValue(true).build());
    private final Setting<Boolean> showDuration = sgGeneral.add(new BoolSetting.Builder()
        .name("show-duration").description("Show the session duration.").defaultValue(true).build());
    private final Setting<Boolean> showBreakdown = sgGeneral.add(new BoolSetting.Builder()
        .name("show-breakdown").description("Show a per-type breakdown.").defaultValue(false).build());

    private final LogService service = LogService.get();

    public StatsHud() {
        super(INFO);
    }

    private record Row(String key, String value) {
    }

    private List<Row> rows(StatsSnapshot snapshot) {
        List<Row> out = new ArrayList<>();
        if (showTotal.get()) out.add(new Row("Messages", Messages.number(snapshot.totalMessages())));
        if (showRate.get()) out.add(new Row("Rate", Messages.decimal(snapshot.messagesPerMinute(), 1) + "/min"));
        if (showPlayers.get()) out.add(new Row("Players", Messages.number(snapshot.uniquePlayers())));
        if (showJoins.get()) out.add(new Row("Joins / Leaves", snapshot.joinEvents() + " / " + snapshot.leaveEvents()));
        if (showDeaths.get()) out.add(new Row("Deaths", Messages.number(snapshot.deathEvents())));
        if (showKeywords.get()) out.add(new Row("Alerts", Messages.number(snapshot.keywordAlerts())));
        if (showDuration.get()) out.add(new Row("Duration", ReportFormatting.duration(snapshot.durationMs())));
        if (showBreakdown.get()) {
            for (MessageType type : MessageType.values()) {
                long count = snapshot.count(type);
                if (count > 0) out.add(new Row(type.toString(), Messages.number(count)));
            }
        }
        return out;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        List<Row> rows = rows(service.snapshot());

        double width = renderer.textWidth("Session", shadow, scale);
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
            "Session", scale, shadow);

        List<Row> rows = rows(service.snapshot());
        if (rows.isEmpty()) {
            HudPanel.line(renderer, this, "No statistics yet", cursor, style.muted(), scale, shadow);
            return;
        }
        for (Row row : rows) {
            HudPanel.keyValue(renderer, this, row.key(), row.value(), cursor, style.muted(), style.text(), scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
