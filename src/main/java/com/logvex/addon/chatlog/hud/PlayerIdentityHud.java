package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.PlayerIdentityTracker;
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
 * HUD listing recently observed player name changes.
 *
 * <p>Only renames the client actually saw are shown, and each line reads {@code old -> new}. A rename
 * of a watched player is tinted with the theme's warning colour so it stands out. When nothing has
 * been observed the element says so rather than showing an empty box.
 */
public class PlayerIdentityHud extends HudElement {
    public static final HudElementInfo<PlayerIdentityHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "player-identity", "Recently observed player renames.",
            PlayerIdentityHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> entries = sgGeneral.add(new IntSetting.Builder()
        .name("entries").description("How many recent renames to list.").defaultValue(3).min(1).sliderRange(1, 10).build());
    private final Setting<Boolean> showTimeAgo = sgGeneral.add(new BoolSetting.Builder()
        .name("show-time-ago").description("Show how long ago each rename was seen.").defaultValue(true).build());

    private final LogService service = LogService.get();

    public PlayerIdentityHud() {
        super(INFO);
    }

    private List<PlayerIdentityTracker.Rename> recent() {
        return service.identities().renames().stream().limit(entries.get()).toList();
    }

    private boolean watched(PlayerIdentityTracker.Rename rename) {
        return service.watchlist().contains(rename.to()) || service.watchlist().contains(rename.from());
    }

    private String line(PlayerIdentityTracker.Rename rename, long now) {
        StringBuilder sb = new StringBuilder();
        sb.append(rename.from()).append(" \u2192 ").append(rename.to());
        if (showTimeAgo.get() && rename.atMs() > 0) sb.append("  ").append(Messages.ago(rename.atMs(), now));
        return sb.toString();
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        for (PlayerIdentityTracker.Rename rename : recent()) lines.add(line(rename, now));
        HudPanel.measure(this, renderer, scale, shadow, "Player Renames", lines);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Player Renames", scale, shadow);

        List<PlayerIdentityTracker.Rename> recent = recent();
        if (recent.isEmpty()) {
            HudPanel.line(renderer, this, "No renames observed", cursor, style.muted(), scale, shadow);
            return;
        }
        long now = System.currentTimeMillis();
        for (PlayerIdentityTracker.Rename rename : recent) {
            Color color = watched(rename) ? style.warn() : style.text();
            HudPanel.line(renderer, this, line(rename, now), cursor, color, scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
