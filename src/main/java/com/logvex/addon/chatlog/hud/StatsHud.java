package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.framework.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

/** HUD summarising the current session statistics. */
public class StatsHud extends HudElement {
    public static final HudElementInfo<StatsHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "session-stats", "Session chat statistics.", StatsHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

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

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background").description("Draw a background behind the HUD.").defaultValue(true).build());
    private final Setting<SettingColor> backgroundColor = sgStyle.add(new ColorSetting.Builder()
        .name("background-color").description("Background color.")
        .defaultValue(new SettingColor(12, 12, 16, 170)).visible(background::get).build());
    private final Setting<SettingColor> titleColor = sgStyle.add(new ColorSetting.Builder()
        .name("title-color").description("Title color.")
        .defaultValue(new SettingColor(140, 200, 255)).build());
    private final Setting<Boolean> customScale = sgStyle.add(new BoolSetting.Builder()
        .name("custom-scale").description("Use a custom text scale instead of the global one.")
        .defaultValue(false).build());
    private final Setting<Double> scale = sgStyle.add(new com.logvex.addon.framework.settings.DoubleSetting.Builder()
        .name("scale").description("Custom scale.").defaultValue(1).min(0.5).sliderRange(0.5, 3)
        .visible(customScale::get).build());

    private final LogService service = LogService.get();

    public StatsHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private List<String> lines(StatsSnapshot snapshot) {
        List<String> out = new ArrayList<>();
        if (showTotal.get()) out.add("Messages: " + snapshot.totalMessages());
        if (showRate.get()) out.add(String.format(java.util.Locale.ROOT, "Rate: %.1f/min", snapshot.messagesPerMinute()));
        if (showPlayers.get()) out.add("Players: " + snapshot.uniquePlayers());
        if (showJoins.get()) out.add("Joins/Leaves: " + snapshot.joinEvents() + "/" + snapshot.leaveEvents());
        if (showDeaths.get()) out.add("Deaths: " + snapshot.deathEvents());
        if (showKeywords.get()) out.add("Keyword alerts: " + snapshot.keywordAlerts());
        if (showDuration.get()) out.add("Duration: " + ReportFormatting.duration(snapshot.durationMs()));
        if (showBreakdown.get()) {
            for (MessageType type : MessageType.values()) {
                long count = snapshot.count(type);
                if (count > 0) out.add("  " + type + ": " + count);
            }
        }
        return out;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        List<String> lines = lines(service.snapshot());

        double width = renderer.textWidth("Session Stats", true, scaleValue);
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, true, scaleValue));

        double height = lineHeight * (1 + Math.max(1, lines.size()));
        setSize(width + 6, height + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        renderer.text("Session Stats", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        List<String> lines = lines(service.snapshot());
        if (lines.isEmpty()) {
            renderer.text("No statistics", left, cursor, MUTED, true, scaleValue);
            return;
        }
        for (String line : lines) {
            renderer.text(line, left, cursor, TEXT, true, scaleValue);
            cursor += lineHeight;
        }
    }
}
