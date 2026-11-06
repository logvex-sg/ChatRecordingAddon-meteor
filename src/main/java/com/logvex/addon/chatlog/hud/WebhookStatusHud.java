package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.WebhookDeliveryLog;
import com.logvex.addon.chatlog.modules.WebhookMonitorModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.modules.Modules;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.framework.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD summarising webhook delivery health.
 *
 * <p>It reads the live {@link WebhookMonitorModule} each frame and shows nothing when that module is
 * absent or disabled, so it never displays stale information from a previous session.
 */
public class WebhookStatusHud extends HudElement {
    public static final HudElementInfo<WebhookStatusHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "webhook-status", "Webhook delivery status.", WebhookStatusHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color GOOD = new Color(120, 220, 130);
    private static final Color BAD = new Color(240, 120, 120);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Boolean> showQueue = sgGeneral.add(new BoolSetting.Builder()
        .name("show-queue")
        .description("Show how many deliveries are queued.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCounts = sgGeneral.add(new BoolSetting.Builder()
        .name("show-counts")
        .description("Show delivered and failed totals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLastError = sgGeneral.add(new BoolSetting.Builder()
        .name("show-last-error")
        .description("Show the last failure reason, if any.")
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
        double lineHeight = renderer.textHeight(true) + 1;
        double width = renderer.textWidth("Webhook", true);
        for (String line : lines()) width = Math.max(width, renderer.textWidth(line, true));
        width = Math.min(width, 260);
        setSize(width + 6, lineHeight * (1 + Math.max(1, lines().size())) + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double lineHeight = renderer.textHeight(true) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        WebhookMonitorModule monitor = module();
        Color titleColour = monitor != null && monitor.isFailing() ? BAD : titleColor.get();
        renderer.text("Webhook", left, cursor, titleColour, true);
        cursor += lineHeight;

        List<String> lines = lines();
        if (lines.isEmpty()) {
            renderer.text("Inactive", left, cursor, MUTED, true);
            return;
        }
        for (String line : lines) {
            renderer.text(line, left, cursor, monitor.isFailing() ? BAD : TEXT, true);
            cursor += lineHeight;
        }
    }
}
