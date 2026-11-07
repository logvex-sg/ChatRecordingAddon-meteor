package com.logvex.addon.notify;

import com.logvex.addon.LogClient;
import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.notify.core.NotificationCenter;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.core.NotificationType;
import com.logvex.addon.notify.gui.NotificationCenterScreen;
import com.logvex.addon.notify.hud.NotificationHud;
import com.logvex.addon.notify.mc.NotificationService;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.DoubleSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;
import net.minecraft.sound.SoundEvents;

import java.util.EnumMap;
import java.util.Map;

/**
 * Surfaces the shared notification stream to the player.
 *
 * <p>The module is only the output side: it listens to {@link NotificationService} and decides what
 * to print and what to beep. Every other Log Client feature publishes through the service, so this
 * module is the single place that controls chat and sound output, and turning it off never loses
 * history.
 */
public class NotificationModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOutput = settings.createGroup("Output");
    private final SettingGroup sgTypes = settings.createGroup("Types");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print accepted notifications to chat. Off by default: the modules that "
            + "raise a notification usually print it themselves, and enabling both would double it.")
        .defaultValue(false)
        .build()
    );

    private final Setting<NotificationPriority> chatPriority = sgGeneral.add(new EnumSetting.Builder<NotificationPriority>()
        .name("chat-min-priority")
        .description("Only print notifications at or above this priority to chat.")
        .defaultValue(NotificationPriority.NORMAL)
        .visible(chatFeedback::get)
        .build()
    );

    private final Setting<Boolean> sound = sgOutput.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a sound for notifications at or above the sound priority.")
        .defaultValue(false)
        .build()
    );

    private final Setting<NotificationPriority> soundPriority = sgOutput.add(new EnumSetting.Builder<NotificationPriority>()
        .name("sound-min-priority")
        .description("Only play a sound for notifications at or above this priority.")
        .defaultValue(NotificationPriority.HIGH)
        .visible(sound::get)
        .build()
    );

    private final Setting<Double> volume = sgOutput.add(new DoubleSetting.Builder()
        .name("volume")
        .description("Notification sound volume.")
        .defaultValue(1.0)
        .min(0)
        .sliderRange(0, 2)
        .visible(sound::get)
        .build()
    );

    private final Setting<Boolean> hud = sgOutput.add(new BoolSetting.Builder()
        .name("hud")
        .description("Show the recent-notifications HUD.")
        .defaultValue(false)
        .onChanged(this::setHudActive)
        .build()
    );

    /** One enable toggle per notification type, so a user can silence a whole category. */
    private final Map<NotificationType, Setting<Boolean>> typeToggles =
        new EnumMap<>(NotificationType.class);

    private final NotificationService service = NotificationService.get();
    private final NotificationCenter.Listener listener = this::onNotification;

    public NotificationModule() {
        super(LogClient.CATEGORY, "notifications",
            "Central notification stream: chat, sound and history for every Log Client feature.");
        for (NotificationType type : NotificationType.values()) {
            typeToggles.put(type, sgTypes.add(new BoolSetting.Builder()
                .name(type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                .description("Show " + type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                    + " notifications.")
                .defaultValue(true)
                .build()));
        }
    }

    @Override
    public void onActivate() {
        service.center().addListener(listener);
        setHudActive(hud.get());
    }

    @Override
    public void onDeactivate() {
        service.center().removeListener(listener);
        setHudActive(false);
    }

    /**
     * Shows or hides the notification HUD element.
     *
     * <p>The element is added once and its own active flag is driven, so repeated toggles never
     * stack duplicate elements. {@link com.logvex.addon.framework.systems.hud.Hud#toggle} is the
     * single supported entry point, which keeps this idempotent.
     */
    private void setHudActive(boolean enabled) {
        com.logvex.addon.framework.systems.hud.Hud hud =
            com.logvex.addon.framework.systems.hud.Hud.get();
        if (enabled && !hud.isActive(NotificationHud.INFO)) {
            hud.add(NotificationHud.INFO, 4, 40);
        } else if (!enabled && hud.isActive(NotificationHud.INFO)) {
            hud.remove(hud.instance(NotificationHud.INFO));
        }
    }

    private void onNotification(Notification notification) {
        if (!isActive()) return;
        if (!isEnabled(notification.type())) return;

        if (chatFeedback.get() && notification.priority().atLeast(chatPriority.get())) {
            String line = notification.line();
            switch (notification.priority()) {
                case CRITICAL, HIGH -> error("%s", line);
                case NORMAL -> info("%s", line);
                case LOW -> {
                    // Low priority stays out of chat unless the user asked for it explicitly.
                    if (chatPriority.get() == NotificationPriority.LOW) info("%s", line);
                }
            }
        }

        if (sound.get() && notification.priority().atLeast(soundPriority.get())) {
            playSound(notification.priority());
        }
    }

    private boolean isEnabled(NotificationType type) {
        Setting<Boolean> toggle = typeToggles.get(type);
        return toggle == null || toggle.get();
    }

    private void playSound(NotificationPriority priority) {
        if (mc.player == null) return;
        float pitch = switch (priority) {
            case CRITICAL -> 0.7f;
            case HIGH -> 1.0f;
            case NORMAL -> 1.3f;
            case LOW -> 1.6f;
        };
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
            volume.get().floatValue(), pitch);
    }

    /** The shared notification centre, for commands and other screens. */
    public NotificationCenter center() {
        return service.center();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WButton open = list.add(theme.button("Notification Center...")).expandX().widget();
        open.action = () -> mc.setScreen(new NotificationCenterScreen(this));

        WButton clear = list.add(theme.button("Clear History")).expandX().widget();
        clear.action = () -> {
            center().clear();
            info("Notification history cleared.");
        };

        WButton test = list.add(theme.button("Send Test Notification")).expandX().widget();
        test.action = () -> service.notify(NotificationType.GENERAL, "notifications",
            "Test", "If you can read this, notifications work.");

        return list;
    }
}
