package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerSession;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;

import java.util.ArrayList;
import java.util.List;

/** HUD showing the most active players seen this session. */
public class ActivityHud extends HudElement {
    public static final HudElementInfo<ActivityHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "activity", "Most active players.", ActivityHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> entries = sgGeneral.add(new IntSetting.Builder()
        .name("entries").description("How many players to list.").defaultValue(5).min(1).sliderRange(1, 15).build());
    private final Setting<Boolean> showMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("show-messages").description("Show each player's message count.").defaultValue(true).build());
    private final Setting<Boolean> showLastSeen = sgGeneral.add(new BoolSetting.Builder()
        .name("show-last-seen").description("Show how recently each player was seen.").defaultValue(false).build());
    private final Setting<Boolean> showOnlineTime = sgGeneral.add(new BoolSetting.Builder()
        .name("show-online-time").description("Show observed online time (a dash when unknown).")
        .defaultValue(false).build());

    private final LogService service = LogService.get();

    public ActivityHud() {
        super(INFO);
    }

    private List<PlayerActivity> top() {
        return service.players().search("").stream().limit(entries.get()).toList();
    }

    private String line(PlayerActivity activity) {
        StringBuilder sb = new StringBuilder(activity.name());
        if (showMessages.get()) sb.append("  ").append(activity.messageCount());
        if (showLastSeen.get()) sb.append("  ").append(ReportFormatting.shortTime(activity.lastSeen()));
        if (showOnlineTime.get()) {
            PlayerSession session = service.sessions().get(activity.name());
            sb.append("  ").append(session == null || !session.hasMeasuredTime()
                ? "-" : ReportFormatting.duration(session.onlineMs(System.currentTimeMillis())));
        }
        return sb.toString();
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        List<String> lines = new ArrayList<>();
        for (PlayerActivity activity : top()) lines.add(line(activity));
        HudPanel.measure(this, renderer, scale, shadow, "Activity", lines);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Activity", scale, shadow);

        List<PlayerActivity> top = top();
        if (top.isEmpty()) {
            HudPanel.line(renderer, this, "No activity yet", cursor, style.muted(), scale, shadow);
            return;
        }
        for (PlayerActivity activity : top) {
            HudPanel.line(renderer, this, line(activity), cursor, style.text(), scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
