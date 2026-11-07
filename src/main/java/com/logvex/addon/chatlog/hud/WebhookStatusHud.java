package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.modules.WebhookMonitorModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;
import com.logvex.addon.framework.systems.modules.Modules;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD summarising webhook delivery health.
 *
 * <p>It reads the live {@link WebhookMonitorModule} each frame and shows "Inactive" when that module
 * is absent or disabled, so it never displays stale information from a previous session. The title
 * turns to the theme's error colour while delivery is failing.
 */
public class WebhookStatusHud extends HudElement {
    public static final HudElementInfo<WebhookStatusHud> INFO =
        new HudElementInfo<>(LogClient.HUD_GROUP, "webhook-status", "Webhook delivery status.", WebhookStatusHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showQueue = sgGeneral.add(new BoolSetting.Builder()
        .name("show-queue").description("Show how many deliveries are queued.").defaultValue(true).build());
    private final Setting<Boolean> showCounts = sgGeneral.add(new BoolSetting.Builder()
        .name("show-counts").description("Show delivered and failed totals.").defaultValue(true).build());
    private final Setting<Boolean> showLastError = sgGeneral.add(new BoolSetting.Builder()
        .name("show-last-error").description("Show the last failure reason, if any.").defaultValue(false).build());

    public WebhookStatusHud() {
        super(INFO);
    }

    private WebhookMonitorModule module() {
        return Modules.get().get(WebhookMonitorModule.class);
    }

    private List<String> lines() {
        WebhookMonitorModule monitor = module();
        if (monitor == null || !monitor.isActive() || monitor.queue() == null) return List.of();

        List<String> out = new ArrayList<>();
        out.add(monitor.stateLabel());
        if (showQueue.get()) out.add(monitor.queue().queued() + " queued");
        if (showCounts.get()) {
            out.add(monitor.queue().delivered() + " delivered, " + monitor.queue().failed() + " failed");
        }
        if (showLastError.get() && monitor.isFailing()) {
            out.add(Messages.truncate(monitor.failureExplanation(), 50));
        }
        return out;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudPanel.measure(this, renderer, scale, shadow, "Webhook", lines());
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        WebhookMonitorModule monitor = module();
        boolean failing = monitor != null && monitor.isFailing();
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Webhook", failing ? style.bad() : style.title(), scale, shadow);

        List<String> lines = lines();
        if (lines.isEmpty()) {
            HudPanel.line(renderer, this, "Inactive", cursor, style.muted(), scale, shadow);
            return;
        }
        Color color = failing ? style.bad() : style.text();
        for (String line : lines) {
            HudPanel.line(renderer, this, line, cursor, color, scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
