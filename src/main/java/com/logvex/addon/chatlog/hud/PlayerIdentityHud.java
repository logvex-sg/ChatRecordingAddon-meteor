package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.PlayerIdentityTracker;
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
 * HUD listing recently observed player name changes.
 *
 * <p>Only renames the client actually saw are shown, and each line reads {@code old -> new}. When
 * nothing has been observed the element says so rather than showing an empty box.
 */
public class PlayerIdentityHud extends HudElement {
    public static final HudElementInfo<PlayerIdentityHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "player-identity", "Recently observed player renames.",
            PlayerIdentityHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color WATCHED = new Color(255, 210, 120);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Integer> entries = sgGeneral.add(new IntSetting.Builder()
        .name("entries")
        .description("How many recent renames to list.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> showTimeAgo = sgGeneral.add(new BoolSetting.Builder()
        .name("show-time-ago")
        .description("Show how long ago each rename was seen.")
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

    public PlayerIdentityHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private List<PlayerIdentityTracker.Rename> recent() {
        return service.identities().renames().stream().limit(entries.get()).toList();
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double width = renderer.textWidth("Player Renames", true, scaleValue);
        for (PlayerIdentityTracker.Rename rename : recent()) {
            width = Math.max(width, renderer.textWidth(line(rename, System.currentTimeMillis()), true, scaleValue));
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

        renderer.text("Player Renames", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        List<PlayerIdentityTracker.Rename> recent = recent();
        if (recent.isEmpty()) {
            renderer.text("No renames observed", left, cursor, MUTED, true, scaleValue);
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerIdentityTracker.Rename rename : recent) {
            boolean watched = service.watchlist().contains(rename.to()) || service.watchlist().contains(rename.from());
            renderer.text(line(rename, now), left, cursor, watched ? WATCHED : TEXT, true, scaleValue);
            cursor += lineHeight;
        }
    }

    private String line(PlayerIdentityTracker.Rename rename, long now) {
        StringBuilder sb = new StringBuilder();
        sb.append(rename.from()).append(" \u2192 ").append(rename.to());
        if (showTimeAgo.get() && rename.atMs() > 0) {
            sb.append("  ").append(Messages.ago(rename.atMs(), now));
        }
        return sb.toString();
    }
}
