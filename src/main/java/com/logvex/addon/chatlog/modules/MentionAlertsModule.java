package com.logvex.addon.chatlog.modules;

import com.logvex.addon.framework.systems.modules.AddonModule;
import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.AlertThrottle;
import com.logvex.addon.chatlog.core.MentionScanner;
import com.logvex.addon.chatlog.mc.LogService;
import meteordevelopment.meteorclient.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.core.NotificationType;
import com.logvex.addon.notify.mc.NotificationService;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Alerts when a name of interest is used in chat.
 *
 * <p>Unlike {@code keyword-alerts}, which matches arbitrary patterns, this watches a list of player
 * names — your own and any you care about — and fires only when one is used as a whole word. A busy
 * server makes "did anyone mention me?" hard to answer by eye, so this raises a notification, an
 * optional sound, and an optional mention embed, and records the hit in the alert log.
 */
public class MentionAlertsModule extends AddonModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOutput = settings.createGroup("Output");

    private final Setting<List<String>> names = sgGeneral.add(new StringListSetting.Builder()
        .name("names")
        .description("Names to watch, one per line. Your own name is added automatically.")
        .defaultValue()
        .build()
    );

    private final Setting<Boolean> includeSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("include-own-name")
        .description("Also alert when your own player name is mentioned.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fromOthersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("others-only")
        .description("Ignore lines you sent yourself.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Shortest gap between two alerts for the same name. Mentions inside the gap "
            + "are counted and reported with the next alert instead of printing a line each.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 60)
        .onChanged(ignored -> rebuildThrottle())
        .build()
    );

    private final Setting<Integer> burstLimit = sgGeneral.add(new IntSetting.Builder()
        .name("burst-limit")
        .description("Most alerts allowed in ten seconds, across every watched name. Keeps a busy "
            + "server from flooding chat even when many different names are hit.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 50)
        .onChanged(ignored -> rebuildThrottle())
        .build()
    );

    private final Setting<Boolean> sound = sgOutput.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a note when a watched name is mentioned.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notify = sgOutput.add(new BoolSetting.Builder()
        .name("notification")
        .description("Raise a notification for each mention.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgOutput.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a chat note for each mention.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> webhook = sgOutput.add(new BoolSetting.Builder()
        .name("webhook")
        .description("Send a mention embed to the shared webhook.")
        .defaultValue(false)
        .build()
    );

    private final LogService service = LogService.get();

    private long lastSeenTimestamp;
    private long totalMentions;
    private long suppressedMentions;
    private AlertThrottle throttle = AlertThrottle.defaults();

    public MentionAlertsModule() {
        super(LogClient.CATEGORY, "mention-alerts",
            "Alerts when your name or a watched name is used in chat.");
    }

    @Override
    public void onActivate() {
        lastSeenTimestamp = System.currentTimeMillis();
        totalMentions = 0;
        suppressedMentions = 0;
        throttle = newThrottle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        List<String> watched = watchedNames();
        if (watched.isEmpty()) return;

        List<ChatEntry> recent = service.recent(50);
        long newest = lastSeenTimestamp;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatEntry entry = recent.get(i);
            if (entry.timestamp() <= lastSeenTimestamp) continue;
            newest = Math.max(newest, entry.timestamp());
            handle(entry, watched);
        }
        lastSeenTimestamp = newest;
    }

    private void handle(ChatEntry entry, List<String> watched) {
        if (fromOthersOnly.get() && isSelf(entry.sender())) return;
        List<String> hits = MentionScanner.find(watched, entry);
        if (hits.isEmpty()) return;

        totalMentions++;
        String names = String.join(", ", hits);

        // A name used constantly, or a server full of different names, would otherwise print a line
        // and play a sound for every single message. The throttle groups a burst into one alert.
        long now = System.currentTimeMillis();
        AlertThrottle.Verdict verdict = throttle.admit(names, now);
        if (verdict != AlertThrottle.Verdict.FIRE) {
            suppressedMentions++;
            return;
        }
        long folded = throttle.takeFolded(names);
        if (folded > 0) names = names + " (+" + folded + " more)";

        String detail = entry.sender().isBlank() ? entry.content() : entry.sender() + ": " + entry.content();
        service.alerts().record(com.logvex.addon.chatlog.core.AlertLog.Kind.KEYWORD, "mention:" + names, detail);

        if (notify.get()) {
            NotificationService.get().notify(NotificationType.IMPORTANT_CHAT, NotificationPriority.HIGH,
                "mention-alerts", "Mentioned: " + names, detail);
        }
        if (sound.get()) playAlert();
        if (chatFeedback.get()) info("Mentioned (%s): %s", names, detail);
        if (webhook.get() && service.hasRelay()) {
            service.notifyMention(service.relayUrl(), service.relayUsername(), hits, entry);
        }
    }

    private AlertThrottle newThrottle() {
        return new AlertThrottle(cooldownSeconds.get() * 1_000L, burstLimit.get(), 10_000L);
    }

    /** Re-reads the throttle settings; called when one of them changes. */
    private void rebuildThrottle() {
        throttle = newThrottle();
    }

    /** How many mentions were folded into a later alert instead of printing their own line. */
    public long suppressedMentions() {
        return suppressedMentions;
    }

    private boolean isSelf(String sender) {
        return mc.player != null && sender != null && sender.equalsIgnoreCase(mc.player.getGameProfile().getName());
    }

    private void playAlert() {
        if (mc.player == null) return;
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.2f);
    }

    /** The names currently watched: the configured list plus the local player when enabled. */
    public List<String> watchedNames() {
        List<String> out = new ArrayList<>();
        if (includeSelf.get() && mc.player != null) {
            String self = mc.player.getGameProfile().getName();
            if (self != null && !self.isBlank()) out.add(self);
        }
        for (String name : names.get()) {
            if (name == null || name.isBlank()) continue;
            String trimmed = name.trim();
            boolean duplicate = out.stream().anyMatch(existing -> existing.equalsIgnoreCase(trimmed));
            if (!duplicate) out.add(trimmed);
        }
        return out;
    }

    /** Total mentions seen since the module was enabled. */
    public long totalMentions() {
        return totalMentions;
    }

    @Override
    public String getInfoString() {
        return totalMentions > 0 ? String.valueOf(totalMentions) : null;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        list.add(theme.label("Watching: " + (watchedNames().isEmpty()
            ? "no names yet" : String.join(", ", watchedNames())))).expandX().widget();
        list.add(theme.label(totalMentions + " mention" + (totalMentions == 1 ? "" : "s")
            + ", " + suppressedMentions + " held back by the throttle")).expandX().widget();
        WButton open = list.add(theme.button("Open Notifications")).expandX().widget();
        open.action = () -> {
            var notifyModule = meteordevelopment.meteorclient.systems.modules.Modules.get()
                .get(com.logvex.addon.notify.NotificationModule.class);
            if (notifyModule != null) {
                mc.setScreen(new com.logvex.addon.notify.gui.NotificationCenterScreen(notifyModule));
            }
        };
        return list;
    }
}
