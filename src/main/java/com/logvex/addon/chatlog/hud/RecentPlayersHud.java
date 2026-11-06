package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerSession;
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
 * HUD listing the players seen most recently, with how long ago and whether they are online.
 *
 * <p>"Online" is only claimed when a join was observed without a matching leave, which is the most
 * the client can honestly know. A player who was already present when the client connected is shown
 * with a dash rather than a fabricated duration.
 */
public class RecentPlayersHud extends HudElement {
    public static final HudElementInfo<RecentPlayersHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "recent-players", "Players seen most recently.",
            RecentPlayersHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color ONLINE = new Color(120, 220, 140);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Integer> entries = sgGeneral.add(new IntSetting.Builder()
        .name("entries")
        .description("How many players to list.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> showTimeAgo = sgGeneral.add(new BoolSetting.Builder()
        .name("show-time-ago")
        .description("Show how long ago each player was last seen.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showOnline = sgGeneral.add(new BoolSetting.Builder()
        .name("show-online")
        .description("Mark players believed to be online with a dot.")
        .defaultValue(true)
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

    public RecentPlayersHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private List<PlayerActivity> recent() {
        return service.players().search("").stream().limit(entries.get()).toList();
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double width = renderer.textWidth("Recent Players", true, scaleValue);
        for (PlayerActivity activity : recent()) {
            width = Math.max(width, renderer.textWidth(line(activity, System.currentTimeMillis()), true, scaleValue));
        }
        double height = lineHeight * (1 + Math.max(1, recent().size()));
        setSize(width + 6, height + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        renderer.text("Recent Players", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        List<PlayerActivity> recent = recent();
        if (recent.isEmpty()) {
            renderer.text("No players seen yet", left, cursor, MUTED, true, scaleValue);
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerActivity activity : recent) {
            boolean online = showOnline.get() && isOnline(activity.name());
            renderer.text(line(activity, now), left, cursor, online ? ONLINE : TEXT, true, scaleValue);
            cursor += lineHeight;
        }
    }

    private boolean isOnline(String name) {
        PlayerSession session = service.sessions().get(name);
        return session != null && session.online();
    }

    private String line(PlayerActivity activity, long now) {
        StringBuilder sb = new StringBuilder();
        if (showOnline.get() && isOnline(activity.name())) sb.append('\u25cf').append(' ');
        sb.append(activity.name());
        if (showTimeAgo.get() && activity.lastSeen() > 0) {
            sb.append("  ").append(Messages.ago(activity.lastSeen(), now));
        }
        return sb.toString();
    }
}
