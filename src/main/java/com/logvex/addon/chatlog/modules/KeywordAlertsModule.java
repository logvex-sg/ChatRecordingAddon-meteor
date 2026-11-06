package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.KeywordMatcher;
import com.logvex.addon.chatlog.core.KeywordRule;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Configurable keyword alerts.
 *
 * <p>Rules are stored as strings and evaluated by {@link KeywordMatcher}. A rule can play a sound,
 * raise a webhook notification, or both. Alerts are always surfaced in the live feed; the optional
 * channels are opt-in per rule.
 */
public class KeywordAlertsModule extends Module {
    private final SettingGroup sgRules = settings.getDefaultGroup();
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    private final Setting<List<String>> rules = sgRules.add(new StringListSetting.Builder()
        .name("rules")
        .description("Keyword rules, one per line: pattern;case=;whole=;sound=;webhook=;enabled=;color=")
        .defaultValue(KeywordRule.defaults().stream().map(KeywordRule::encode).toList())
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgRules.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a chat note when a keyword matches.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> webhookUrl = sgWebhook.add(new com.logvex.addon.framework.settings.StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL used by rules that enable webhook notifications. Kept out of saved data.")
        .defaultValue("")
        .wide()
        .build()
    );

    private final Setting<String> webhookUsername = sgWebhook.add(new com.logvex.addon.framework.settings.StringSetting.Builder()
        .name("webhook-username")
        .description("Display name used for webhook alerts.")
        .defaultValue("Log Addon")
        .build()
    );

    private final Setting<Boolean> sendSummary = sgWebhook.add(new BoolSetting.Builder()
        .name("periodic-summary")
        .description("Also send a periodic chat summary to the webhook.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> summaryMinutes = sgWebhook.add(new com.logvex.addon.framework.settings.IntSetting.Builder()
        .name("summary-interval-minutes")
        .description("Minutes between periodic summaries.")
        .defaultValue(15)
        .min(1)
        .sliderRange(1, 120)
        .visible(sendSummary::get)
        .build()
    );

    private final LogService service = LogService.get();

    private List<KeywordRule> parsed = List.of();
    private long lastSummaryAt;
    private long lastSeenTimestamp;

    public KeywordAlertsModule() {
        super(LogAddon.CATEGORY, "keyword-alerts", "Alerts on configurable keywords with sound and webhook options.");
    }

    @Override
    public void onActivate() {
        parsed = parseRules();
        service.setKeywordRules(parsed);
        lastSummaryAt = System.currentTimeMillis();
        lastSeenTimestamp = System.currentTimeMillis();
    }

    @Override
    public void onDeactivate() {
        service.setKeywordRules(List.of());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        List<KeywordRule> current = parseRules();
        if (!current.equals(parsed)) {
            parsed = current;
            service.setKeywordRules(parsed);
        }

        evaluateRecent();

        if (sendSummary.get()) {
            long interval = summaryMinutes.get() * 60_000L;
            if (System.currentTimeMillis() - lastSummaryAt >= interval) {
                lastSummaryAt = System.currentTimeMillis();
                List<ChatEntry> recent = service.recent(200);
                if (!recent.isEmpty()) service.notifySummary(webhookUrl.get(), webhookUsername.get(), recent);
            }
        }
    }

    private void evaluateRecent() {
        List<ChatEntry> recent = service.recent(50);
        long newest = lastSeenTimestamp;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatEntry entry = recent.get(i);
            if (entry.timestamp() <= lastSeenTimestamp) continue;
            newest = Math.max(newest, entry.timestamp());
            for (KeywordRule rule : KeywordMatcher.matches(parsed, entry)) {
                service.stats().recordKeyword(rule.pattern());
                com.logvex.addon.notify.mc.NotificationService.get().notify(
                    com.logvex.addon.notify.core.NotificationType.IMPORTANT_CHAT,
                    com.logvex.addon.notify.core.NotificationPriority.HIGH,
                    "keyword-alerts", rule.pattern(),
                    entry.sender().isBlank() ? entry.content() : entry.sender() + ": " + entry.content());
                if (rule.sound()) playAlert();
                if (rule.webhook()) service.notifyKeyword(webhookUrl.get(), webhookUsername.get(), rule, entry);
                if (chatFeedback.get()) {
                    warning("Keyword '%s' matched in %s", rule.pattern(), entry.sender().isBlank() ? "chat" : entry.sender());
                }
            }
        }
        lastSeenTimestamp = newest;
    }

    private void playAlert() {
        if (mc.player == null) return;
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
    }

    /** Current rules, parsed from the settings. */
    public List<KeywordRule> currentRules() {
        return parsed;
    }

    public String webhookUrl() {
        return webhookUrl.get();
    }

    public String webhookUsername() {
        return webhookUsername.get();
    }

    public void testWebhook(com.logvex.addon.framework.systems.modules.Module feedbackModule) {
        service.testWebhook(webhookUrl.get(), webhookUsername.get(), (success, message) -> {
            if (feedbackModule != null) {
                if (success) feedbackModule.info("Webhook test succeeded.");
                else feedbackModule.warning("Webhook test failed: %s", message);
            }
        });
    }

    private List<KeywordRule> parseRules() {
        List<KeywordRule> out = new ArrayList<>();
        for (String encoded : rules.get()) {
            KeywordRule rule = KeywordRule.decode(encoded);
            if (rule != null) out.add(rule);
        }
        return out;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton manage = list.add(theme.button("Manage Keyword Rules")).expandX().widget();
        manage.action = () -> {
            ChatLoggerModule logger = com.logvex.addon.framework.systems.modules.Modules.get()
                .get(ChatLoggerModule.class);
            if (logger != null) mc.setScreen(new com.logvex.addon.chatlog.gui.LogDashboardScreen(logger, 3));
        };
        WButton test = list.add(theme.button("Test Webhook")).expandX().widget();
        test.action = () -> testWebhook(this);
        return list;
    }
}
