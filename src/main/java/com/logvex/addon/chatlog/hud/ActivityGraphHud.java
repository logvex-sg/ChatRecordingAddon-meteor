package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.ActivityHistogram;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.framework.utils.render.color.SettingColor;

import java.util.List;

/**
 * HUD drawing recent chat activity as a bar graph.
 *
 * <p>The data is the shared per-minute {@link ActivityHistogram}, so the graph costs nothing to
 * build: it only reads the counters and draws. Bars are scaled to the peak in the window, and the
 * window is clamped so the graph never grows past a sensible width.
 */
public class ActivityGraphHud extends HudElement {
    public static final HudElementInfo<ActivityGraphHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "activity-graph", "Recent chat activity as a graph.",
            ActivityGraphHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color GRID = new Color(70, 70, 84);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Integer> minutes = sgGeneral.add(new IntSetting.Builder()
        .name("minutes")
        .description("How many minutes of history the graph shows.")
        .defaultValue(30)
        .min(5)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Boolean> showPeak = sgGeneral.add(new BoolSetting.Builder()
        .name("show-peak")
        .description("Show the busiest minute and its count above the graph.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTotal = sgGeneral.add(new BoolSetting.Builder()
        .name("show-total")
        .description("Show how many messages the window covers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a background behind the graph.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgStyle.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color.")
        .defaultValue(new SettingColor(12, 12, 16, 170))
        .visible(background::get)
        .build()
    );

    private final Setting<SettingColor> barColor = sgStyle.add(new ColorSetting.Builder()
        .name("bar-color")
        .description("Bar color.")
        .defaultValue(new SettingColor(120, 200, 255))
        .build()
    );

    private final Setting<SettingColor> titleColor = sgStyle.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Title color.")
        .defaultValue(new SettingColor(140, 200, 255))
        .build()
    );

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
        int count = shownMinutes();
        double graphWidth = count * (BAR_WIDTH + BAR_GAP);
        double width = Math.max(renderer.textWidth("Activity", true), graphWidth);
        double height = GRAPH_HEIGHT;
        if (showPeak.get()) height += renderer.textHeight(true) + 1;
        if (showTotal.get()) height += renderer.textHeight(true) + 1;
        height += renderer.textHeight(true) + 1;
        setSize(width + 6, height + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double lineHeight = renderer.textHeight(true) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        renderer.text("Activity", left, cursor, titleColor.get(), true);
        cursor += lineHeight;

        List<ActivityHistogram.Bucket> buckets = buckets();
        long peak = service.activity().peak(shownMinutes());

        if (showPeak.get()) {
            String line = peak == 0
                ? "No activity yet"
                : "Peak " + Messages.plural(peak, "message") + "/min";
            renderer.text(line, left, cursor, peak == 0 ? MUTED : TEXT, true);
            cursor += lineHeight;
        }
        if (showTotal.get()) {
            long total = service.activity().total(shownMinutes());
            renderer.text(Messages.plural(total, "message") + " in " + shownMinutes() + "m",
                left, cursor, MUTED, true);
            cursor += lineHeight;
        }

        double baseY = cursor + GRAPH_HEIGHT;
        double barLeft = left;
        for (ActivityHistogram.Bucket bucket : buckets) {
            double fraction = peak == 0 ? 0 : (double) bucket.count() / peak;
            double barHeight = Math.max(bucket.count() == 0 ? 1 : 2, fraction * GRAPH_HEIGHT);
            if (bucket.count() == 0) {
                renderer.quad(barLeft, baseY - 1, BAR_WIDTH, 1, GRID);
            } else {
                renderer.quad(barLeft, baseY - barHeight, BAR_WIDTH, barHeight, barColor.get());
            }
            barLeft += BAR_WIDTH + BAR_GAP;
        }
    }
}
