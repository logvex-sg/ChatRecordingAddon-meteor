package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
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

/** HUD showing the most active players seen this session. */
public class ActivityHud extends HudElement {
    public static final HudElementInfo<ActivityHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "activity", "Most active players.", ActivityHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Integer> entries = sgGeneral.add(new IntSetting.Builder()
        .name("entries")
        .description("How many players to list.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 15)
        .build()
    );

    private final Setting<Boolean> showMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("show-messages")
        .description("Show each player's message count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLastSeen = sgGeneral.add(new BoolSetting.Builder()
        .name("show-last-seen")
        .description("Show how recently each player was seen.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showOnlineTime = sgGeneral.add(new BoolSetting.Builder()
        .name("show-online-time")
        .description("Show each player's observed online time (a dash when unknown).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a background behind the HUD.")
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

    private final Setting<SettingColor> titleColor = sgStyle.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Title color.")
        .defaultValue(new SettingColor(140, 200, 255))
        .build()
    );

    private final Setting<Boolean> customScale = sgStyle.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Use a custom text scale instead of the global one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgStyle.add(new com.logvex.addon.framework.settings.DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .visible(customScale::get)
        .build()
    );

    private final LogService service = LogService.get();

    public ActivityHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private List<PlayerActivity> top() {
        return service.players().search("").stream().limit(entries.get()).toList();
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double width = renderer.textWidth("Player Activity", true, scaleValue);
        for (PlayerActivity activity : top()) {
            width = Math.max(width, renderer.textWidth(line(activity), true, scaleValue));
        }
        double height = lineHeight * (1 + Math.max(1, top().size()));
        setSize(width + 6, height + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        renderer.text("Player Activity", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        List<PlayerActivity> top = top();
        if (top.isEmpty()) {
            renderer.text("No activity yet", left, cursor, MUTED, true, scaleValue);
            return;
        }
        for (PlayerActivity activity : top) {
            renderer.text(line(activity), left, cursor, TEXT, true, scaleValue);
            cursor += lineHeight;
        }
    }

    private String line(PlayerActivity activity) {
        StringBuilder sb = new StringBuilder(activity.name());
        if (showMessages.get()) sb.append("  ").append(activity.messageCount());
        if (showLastSeen.get()) sb.append("  ").append(ReportFormatting.shortTime(activity.lastSeen()));
        if (showOnlineTime.get()) {
            com.logvex.addon.chatlog.core.PlayerSession session = service.sessions().get(activity.name());
            sb.append("  ").append(session == null || !session.hasMeasuredTime()
                ? "-" : ReportFormatting.duration(session.onlineMs(System.currentTimeMillis())));
        }
        return sb.toString();
    }
}
