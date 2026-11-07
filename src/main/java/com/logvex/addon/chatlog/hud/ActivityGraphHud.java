package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.ActivityHistogram;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;

import java.util.List;

/**
 * HUD drawing recent chat activity as a bar graph.
 *
 * <p>The data is the shared per-minute {@link ActivityHistogram}, so the graph costs nothing to build:
 * it only reads the counters and draws. Bars are scaled to the peak in the window, and the window is
 * clamped so the graph never grows past a sensible width.
 */
public class ActivityGraphHud extends HudElement {
    public static final HudElementInfo<ActivityGraphHud> INFO =
        new HudElementInfo<>(LogClient.HUD_GROUP, "activity-graph", "Recent chat activity as a graph.",
            ActivityGraphHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> minutes = sgGeneral.add(new IntSetting.Builder()
        .name("minutes").description("How many minutes of history the graph shows.")
        .defaultValue(30).min(5).sliderRange(5, 120).build());
    private final Setting<Boolean> showPeak = sgGeneral.add(new BoolSetting.Builder()
        .name("show-peak").description("Show the busiest minute and its count above the graph.")
        .defaultValue(true).build());
    private final Setting<Boolean> showTotal = sgGeneral.add(new BoolSetting.Builder()
        .name("show-total").description("Show how many messages the window covers.").defaultValue(true).build());

    private static final double BAR_WIDTH = 3;
    private static final double BAR_GAP = 1;
    private static final double GRAPH_HEIGHT = 22;

    private final LogService service = LogService.get();

    public ActivityGraphHud() {
        super(INFO);
    }

    private int shownMinutes() {
        return Math.max(5, Math.min(minutes.get(), service.activity().capacity()));
    }

    private List<ActivityHistogram.Bucket> buckets() {
        return service.activity().recent(shownMinutes());
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        int count = shownMinutes();
        double graphWidth = count * (BAR_WIDTH + BAR_GAP);
        double width = Math.max(renderer.textWidth("Activity", shadow, scale), graphWidth);
        double lineHeight = renderer.textHeight(shadow, scale) + HudPanel.LINE_GAP;
        int extraRows = 1; // the title
        if (showPeak.get()) extraRows++;
        if (showTotal.get()) extraRows++;
        setSize(width + HudPanel.PAD * 2, lineHeight * extraRows + GRAPH_HEIGHT + HudPanel.PAD * 2 + 2);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Activity", scale, shadow);

        long peak = service.activity().peak(shownMinutes());
        if (showPeak.get()) {
            String text = peak == 0 ? "No activity yet" : "Peak " + Messages.plural(peak, "message") + "/min";
            HudPanel.line(renderer, this, text, cursor, peak == 0 ? style.muted() : style.text(), scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
        if (showTotal.get()) {
            long total = service.activity().total(shownMinutes());
            HudPanel.line(renderer, this, Messages.plural(total, "message") + " in " + shownMinutes() + "m",
                cursor, style.muted(), scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }

        double baseY = cursor + GRAPH_HEIGHT;
        double barLeft = x + HudPanel.PAD;
        for (ActivityHistogram.Bucket bucket : buckets()) {
            double fraction = peak == 0 ? 0 : (double) bucket.count() / peak;
            double barHeight = Math.max(bucket.count() == 0 ? 1 : 2, fraction * GRAPH_HEIGHT);
            if (bucket.count() == 0) {
                renderer.quad(barLeft, baseY - 1, BAR_WIDTH, 1, style.track());
            } else {
                renderer.quad(barLeft, baseY - barHeight, BAR_WIDTH, barHeight, style.accent());
            }
            barLeft += BAR_WIDTH + BAR_GAP;
        }
    }
}
