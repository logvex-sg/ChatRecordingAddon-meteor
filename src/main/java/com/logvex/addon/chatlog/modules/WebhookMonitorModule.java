package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.WebhookDeliveryLog;
import com.logvex.addon.chatlog.core.WebhookQueue;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.systems.modules.Modules;

/**
 * Watches the shared webhook queue and reports its health.
 *
 * <p>The queue itself lives in {@link com.logvex.addon.chatlog.mc.LogService}; this module adds a
 * bounded delivery history and a chat notice when a delivery fails, so a broken webhook is visible
 * instead of silently dropping alerts. The URL is never stored or displayed — only the host.
 */
public class WebhookMonitorModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notifyOnFailure = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-on-failure")
        .description("Print a chat note when a webhook delivery fails.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyOnRecovery = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-on-recovery")
        .description("Print a chat note when a delivery succeeds after a failure.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> historySize = sgGeneral.add(new com.logvex.addon.framework.settings.IntSetting.Builder()
        .name("history-size")
        .description("How many delivery attempts to keep in the history.")
        .defaultValue(100)
        .min(10)
        .sliderRange(10, 500)
        .build()
    );

    private final WebhookDeliveryLog history = new WebhookDeliveryLog();
    private WebhookQueue queue;

    private int lastDelivered;
    private int lastFailed;
    private int lastRetries;
    private boolean hadFailure;
    private boolean dirty;

    public WebhookMonitorModule() {
        super(LogClient.CATEGORY, "webhook-monitor",
            "Monitors webhook delivery health, queue depth and failure reasons.");
    }

    @Override
    public void onActivate() {
        ChatLoggerModule logger = Modules.get().get(ChatLoggerModule.class);
        queue = logger == null ? null : logger.webhookQueue();
        if (queue != null) {
            lastDelivered = queue.delivered();
            lastFailed = queue.failed();
            lastRetries = queue.retries();
            hadFailure = lastFailed > 0;
        }
    }

    @Override
    public void onDeactivate() {
        // Nothing to release: the queue is owned by the logging service.
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        WebhookQueue current = queue;
        if (current == null) return;

        // Apply the history cap only when it actually changed, so a tick costs one int compare.
        if (history.capacity() != historySize.get()) history.setCapacity(historySize.get());

        int delivered = current.delivered();
        int failed = current.failed();
        int retries = current.retries();

        if (delivered != lastDelivered) {
            record(current, true, delivered - lastDelivered, "Delivered");
            lastDelivered = delivered;
            if (hadFailure && notifyOnRecovery.get()) {
                hadFailure = false;
                info("Webhook deliveries are succeeding again.");
            }
        }
        if (failed != lastFailed) {
            record(current, false, failed - lastFailed, current.lastMessage());
            lastFailed = failed;
            hadFailure = true;
            com.logvex.addon.chatlog.mc.LogService.get().alerts().record(
                com.logvex.addon.chatlog.core.AlertLog.Kind.WEBHOOK, "Delivery failed",
                safeMessage(current.lastMessage()));
            if (notifyOnFailure.get()) {
                warning("Webhook delivery failed. %s", safeMessage(current.lastMessage()));
            }
        }
        if (retries != lastRetries) {
            lastRetries = retries;
            dirty = true;
        }
    }

    private void record(WebhookQueue current, boolean success, int count, String message) {
        ChatLoggerModule logger = Modules.get().get(ChatLoggerModule.class);
        String host = logger == null ? "" : WebhookDeliveryLog.hostOf(logger.webhookUrl());
        int attempt = Math.max(1, count);
        for (int i = 0; i < Math.max(1, Math.abs(count)); i++) {
            history.add(System.currentTimeMillis(), host, success, attempt, message);
        }
        dirty = true;
    }

    /** The delivery history, newest first. */
    public WebhookDeliveryLog history() {
        return history;
    }

    public WebhookQueue queue() {
        return queue;
    }

    /** A short status line, e.g. {@code Idle, 12 delivered, 0 failed}. */
    public String statusLine() {
        WebhookQueue current = queue;
        if (current == null) return "Unavailable";
        return stateLabel() + ", " + current.delivered() + " delivered, " + current.failed()
            + " failed, " + current.queued() + " queued";
    }

    /** The queue state as a human word, e.g. {@code Idle} or {@code Retrying...}. */
    public String stateLabel() {
        WebhookQueue current = queue;
        if (current == null) return "Unavailable";
        return switch (current.state()) {
            case DISABLED -> "Disabled";
            case SENDING -> "Sending...";
            case RETRYING -> "Retrying...";
            case FAILED -> "Failed";
            case IDLE -> "Idle";
        };
    }

    /** True when the last delivery attempt failed and has not yet succeeded. */
    public boolean isFailing() {
        return hadFailure;
    }

    /** A failure explanation with no URL in it. */
    public String failureExplanation() {
        WebhookQueue current = queue;
        if (current == null) return "The webhook queue is unavailable.";
        if (!hadFailure) return "No recent failures.";
        return "Last error: " + safeMessage(current.lastMessage());
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "unknown error";
        // Defensive: never let a URL slip into chat even if the queue reported one.
        return Messages.truncate(message.replaceAll("https?://\\S+", "[webhook]"), 120);
    }

    @Override
    public String getInfoString() {
        WebhookQueue current = queue;
        return current == null ? null : current.delivered() + "/" + (current.delivered() + current.failed());
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton test = list.add(theme.button("Test Webhook")).expandX().widget();
        test.action = () -> {
            ChatLoggerModule logger = Modules.get().get(ChatLoggerModule.class);
            if (logger != null) logger.sendReport(true);
        };
        WButton clear = list.add(theme.button("Clear History")).expandX().widget();
        clear.action = () -> {
            history.clear();
            info("Delivery history cleared.");
        };
        return list;
    }
}
