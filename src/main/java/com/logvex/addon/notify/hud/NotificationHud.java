package com.logvex.addon.notify.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.common.Messages;
import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.mc.NotificationService;
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

import java.util.List;

/**
 * A compact HUD listing the most recent notifications.
 *
 * <p>It reads the shared centre on every frame rather than caching, so a notification raised by any
 * module shows up immediately and clearing the history clears the HUD. Priority drives the line
 * colour through the shared palette, so a critical line is the same red here as everywhere else.
 */
public class NotificationHud extends HudElement {
    public static final HudElementInfo<NotificationHud> INFO = new HudElementInfo<>(
        LogClient.HUD_GROUP, "notifications", "Recent Log Client notifications.", NotificationHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> lines = sgGeneral.add(new IntSetting.Builder()
        .name("lines").description("How many recent notifications to show.").defaultValue(5).min(1).sliderRange(1, 15).build());
    private final Setting<Boolean> showTimestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("timestamps").description("Prefix each line with its time.").defaultValue(false).build());
    private final Setting<Boolean> showSource = sgGeneral.add(new BoolSetting.Builder()
        .name("show-source").description("Prefix each line with the module that raised it.").defaultValue(false).build());

    private final NotificationService service = NotificationService.get();

    public NotificationHud() {
        super(INFO);
    }

    private List<Notification> entries() {
        return service.center().recent(lines.get());
    }

    private String text(Notification notification) {
        StringBuilder sb = new StringBuilder();
        if (showTimestamp.get()) sb.append(Messages.clock(notification.timestamp())).append(' ');
        if (showSource.get() && !notification.source().isEmpty()) {
            sb.append('[').append(notification.source()).append("] ");
        }
        String body = notification.title().isEmpty() ? notification.message()
            : (notification.message().isEmpty()
                ? notification.title()
                : notification.title() + " " + notification.message());
        sb.append(Messages.truncate(body, 48));
        return sb.toString();
    }

    private Color colorFor(NotificationPriority priority, HudStyle style) {
        return switch (priority) {
            case CRITICAL -> style.bad();
            case HIGH -> style.warn();
            case NORMAL -> style.text();
            case LOW -> style.muted();
        };
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        List<Notification> entries = entries();
        double width = renderer.textWidth("Notifications", shadow, scale);
        for (Notification notification : entries) width = Math.max(width, renderer.textWidth(text(notification), shadow, scale));
        double lineHeight = renderer.textHeight(shadow, scale) + HudPanel.LINE_GAP;
        setSize(width + HudPanel.PAD * 2, lineHeight * (Math.max(1, entries.size()) + 1) + HudPanel.PAD * 2);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Notifications", scale, shadow);

        List<Notification> entries = entries();
        if (entries.isEmpty()) {
            HudPanel.line(renderer, this, "No notifications", cursor, style.muted(), scale, shadow);
            return;
        }
        for (Notification notification : entries) {
            HudPanel.line(renderer, this, text(notification), cursor,
                colorFor(notification.priority(), style), scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
