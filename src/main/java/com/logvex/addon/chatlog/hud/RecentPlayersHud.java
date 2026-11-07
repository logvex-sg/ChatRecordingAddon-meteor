package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerSession;
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
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD listing the players seen most recently, with how long ago and whether they are online.
 *
 * <p>"Online" is only claimed when a join was observed without a matching leave, which is the most the
 * client can honestly know. A player already present when the client connected is shown with a dash
 * rather than a fabricated duration. The online marker is a filled dot drawn beside the name, so it
 * reads the same at every text scale.
 */
public class RecentPlayersHud extends HudElement {
    public static final HudElementInfo<RecentPlayersHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "recent-players", "Players seen most recently.",
            RecentPlayersHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> entries = sgGeneral.add(new IntSetting.Builder()
        .name("entries").description("How many players to list.").defaultValue(5).min(1).sliderRange(1, 20).build());
    private final Setting<Boolean> showTimeAgo = sgGeneral.add(new BoolSetting.Builder()
        .name("show-time-ago").description("Show how long ago each player was last seen.").defaultValue(true).build());
    private final Setting<Boolean> showOnline = sgGeneral.add(new BoolSetting.Builder()
        .name("show-online").description("Mark players believed to be online with a dot.").defaultValue(true).build());

    private final LogService service = LogService.get();

    public RecentPlayersHud() {
        super(INFO);
    }

    private List<PlayerActivity> recent() {
        return service.players().search("").stream().limit(entries.get()).toList();
    }

    private boolean isOnline(String name) {
        PlayerSession session = service.sessions().get(name);
        return session != null && session.online();
    }

    private String line(PlayerActivity activity, long now) {
        StringBuilder sb = new StringBuilder();
        if (showOnline.get()) sb.append(isOnline(activity.name()) ? "\u25cf " : "  ");
        sb.append(activity.name());
        if (showTimeAgo.get() && activity.lastSeen() > 0) {
            sb.append("  ").append(Messages.ago(activity.lastSeen(), now));
        }
        return sb.toString();
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        for (PlayerActivity activity : recent()) lines.add(line(activity, now));
        HudPanel.measure(this, renderer, scale, shadow, "Recent Players", lines);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Recent Players", scale, shadow);

        List<PlayerActivity> recent = recent();
        if (recent.isEmpty()) {
            HudPanel.line(renderer, this, "No players seen yet", cursor, style.muted(), scale, shadow);
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerActivity activity : recent) {
            Color color = showOnline.get() && isOnline(activity.name()) ? style.good() : style.text();
            HudPanel.line(renderer, this, line(activity, now), cursor, color, scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
