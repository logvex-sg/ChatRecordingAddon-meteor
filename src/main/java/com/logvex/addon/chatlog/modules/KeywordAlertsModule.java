package com.logvex.addon.chatlog.modules;

import com.logvex.addon.framework.systems.modules.AddonModule;
import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.AlertThrottle;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.KeywordMatcher;
import com.logvex.addon.chatlog.core.KeywordRule;
import com.logvex.addon.chatlog.mc.LogService;
import meteordevelopment.meteorclient.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
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
public class KeywordAlertsModule extends AddonModule {
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

    private final Setting<Integer> cooldownSeconds = sgRules.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Shortest gap between two alerts for the same rule. Matches inside the gap are "
            + "counted and reported with the next alert instead of printing a line each.")
        .defaultValue(2)
        .range(0, 3600)
        .sliderRange(0, 60)
        .onChanged(ignored -> rebuildThrottle())
        .build()
    );

    private final Setting<Integer> burstLimit = sgRules.add(new IntSetting.Builder()
        .name("burst-limit")
        .description("Most alerts allowed in ten seconds, across every rule. Keeps a common word "
            + "from flooding chat and the webhook.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 50)
        .onChanged(ignored -> rebuildThrottle())
        .build()
    );

    private final Setting<String> webhookUrl = sgWebhook.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL used by rules that enable webhook notifications. Kept out of saved data.")
        .defaultValue("")
        .wide()
        .build()
    );

    private final Setting<String> webhookUsername = sgWebhook.add(new meteordevelopment.meteorclient.settings.StringSetting.Builder()
        .name("webhook-username")
        .description("Display name used for webhook alerts.")
        .defaultValue("Log Client")
        .build()
    );

    private final Setting<Boolean> sendSummary = sgWebhook.add(new BoolSetting.Builder()
        .name("periodic-summary")
        .description("Also send a periodic chat summary to the webhook.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> summaryMinutes = sgWebhook.add(new meteordevelopment.meteorclient.settings.IntSetting.Builder()
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
    private long suppressedMatches;
    private AlertThrottle throttle = AlertThrottle.defaults();

    public KeywordAlertsModule() {
        super(LogClient.CATEGORY, "keyword-alerts", "Alerts on configurable keywords with sound and webhook options.");
    }

    @Override
    public void onActivate() {
        parsed = parseRules();
        service.setKeywordRules(parsed);
        lastSummaryAt = System.currentTimeMillis();
        lastSeenTimestamp = System.currentTimeMillis();
        suppressedMatches = 0;
        throttle = newThrottle();
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
                if (!recent.isEmpty()) service.notifySummary(targetUrl(), targetUsername(), recent);
            }
        }
    }

    /** The shared relay target when one is published, else this module's own URL. */
    private String targetUrl() {
        return service.hasRelay() ? service.relayUrl() : webhookUrl.get();
    }

    private String targetUsername() {
        return service.hasRelay() ? service.relayUsername() : webhookUsername.get();
    }

    private void evaluateRecent() {
        // Drop throttle state for rules the user removed, so editing the list does not leave keys
        // that can never fire again.
        throttle.retain(parsed.stream().map(KeywordRule::pattern).toList());

        List<ChatEntry> recent = service.recent(50);
        long newest = lastSeenTimestamp;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatEntry entry = recent.get(i);
            if (entry.timestamp() <= lastSeenTimestamp) continue;
            newest = Math.max(newest, entry.timestamp());
            for (KeywordRule rule : KeywordMatcher.matches(parsed, entry)) {
                service.stats().recordKeyword(rule.pattern());

                // A rule that matches a common word would otherwise alert on every message. The
                // throttle folds a burst into one alert plus a count.
                AlertThrottle.Verdict verdict = throttle.admit(rule.pattern(), System.currentTimeMillis());
                if (verdict != AlertThrottle.Verdict.FIRE) {
                    // Counted per matching rule, which is the same unit as the alert count, so the
                    // two figures cannot disagree.
                    suppressedMatches++;
                    continue;
                }
                long folded = throttle.takeFolded(rule.pattern());
                String label = folded > 0 ? rule.pattern() + " (+" + folded + " more)" : rule.pattern();
                service.alerts().record(com.logvex.addon.chatlog.core.AlertLog.Kind.KEYWORD,
                    label, entry.sender().isBlank() ? entry.content() : entry.sender() + ": " + entry.content());
                com.logvex.addon.notify.mc.NotificationService.get().notify(
                    com.logvex.addon.notify.core.NotificationType.IMPORTANT_CHAT,
                    com.logvex.addon.notify.core.NotificationPriority.HIGH,
                    "keyword-alerts", label,
                    entry.sender().isBlank() ? entry.content() : entry.sender() + ": " + entry.content());
                if (rule.sound()) playAlert();
                if (rule.webhook()) service.notifyKeyword(targetUrl(), targetUsername(), rule, entry);
                if (chatFeedback.get()) {
                    warning("Keyword '%s' matched in %s", label, entry.sender().isBlank() ? "chat" : entry.sender());
                }
            }
        }
        lastSeenTimestamp = newest;
    }

    private void playAlert() {
        if (mc.player == null) return;
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
    }

    private AlertThrottle newThrottle() {
        return new AlertThrottle(cooldownSeconds.get() * 1_000L, burstLimit.get(), 10_000L);
    }

    private void rebuildThrottle() {
        throttle = newThrottle();
    }

    /** How many matches were folded into a later alert instead of printing their own line. */
    public long suppressedMatches() {
        return suppressedMatches;
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

    public void testWebhook(meteordevelopment.meteorclient.systems.modules.Module feedbackModule) {
        service.testWebhook(targetUrl(), targetUsername(), (success, message) -> {
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
            ChatLoggerModule logger = meteordevelopment.meteorclient.systems.modules.Modules.get()
                .get(ChatLoggerModule.class);
            if (logger != null) mc.setScreen(new com.logvex.addon.chatlog.gui.LogDashboardScreen(logger, 3));
        };
        WButton test = list.add(theme.button("Test Webhook")).expandX().widget();
        test.action = () -> testWebhook(this);
        return list;
    }
}
