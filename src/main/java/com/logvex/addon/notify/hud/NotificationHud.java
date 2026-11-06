package com.logvex.addon.notify.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.common.Messages;
import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.mc.NotificationService;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.DoubleSetting;
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
 * A compact HUD listing the most recent notifications.
 *
 * <p>It reads the shared centre on every frame rather than caching, so a notification raised by any
 * module shows up immediately and clearing the history clears the HUD. The list length is bounded by
 * a setting, so the element can never grow to fill the screen.
 */
public class NotificationHud extends HudElement {
    public static final HudElementInfo<NotificationHud> INFO = new HudElementInfo<>(
        LogAddon.HUD_GROUP, "notifications", "Recent Log Addon notifications.", NotificationHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color LOW = new Color(170, 170, 170);
    private static final Color HIGH = new Color(255, 200, 120);
    private static final Color CRITICAL = new Color(255, 140, 140);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Integer> lines = sgGeneral.add(new IntSetting.Builder()
        .name("lines")
        .description("How many recent notifications to show.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 15)
        .build()
    );

    private final Setting<Boolean> showTimestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("timestamps")
        .description("Prefix each line with its time.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showSource = sgGeneral.add(new BoolSetting.Builder()
        .name("show-source")
        .description("Prefix each line with the module that raised it.")
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

    private final Setting<Double> scale = sgStyle.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .visible(customScale::get)
        .build()
    );

    private final NotificationService service = NotificationService.get();

    public NotificationHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private List<Notification> entries() {
        return service.center().recent(lines.get());
    }

    private String render(Notification notification) {
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

    private static Color colorFor(NotificationPriority priority) {
        return switch (priority) {
            case CRITICAL -> CRITICAL;
            case HIGH -> HIGH;
            case NORMAL -> TEXT;
            case LOW -> LOW;
        };
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        List<Notification> entries = entries();

        double width = renderer.textWidth("Notifications", true, scaleValue);
        for (Notification notification : entries) {
            width = Math.max(width, renderer.textWidth(render(notification), true, scaleValue));
        }

        double height = lineHeight * (1 + Math.max(1, entries.size()));
        setSize(width + 6, height + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        renderer.text("Notifications", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        List<Notification> entries = entries();
        if (entries.isEmpty()) {
            renderer.text("No notifications", left, cursor, MUTED, true, scaleValue);
            return;
        }
        for (Notification notification : entries) {
            renderer.text(render(notification), left, cursor, colorFor(notification.priority()),
                true, scaleValue);
            cursor += lineHeight;
        }
    }
}
