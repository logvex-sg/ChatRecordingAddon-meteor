package com.logvex.addon.assistant.modules;

import com.logvex.addon.assistant.core.ModCall;
import com.logvex.addon.automation.modules.AutomationModule;
import com.logvex.addon.chatlog.modules.WebhookRelayModule;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.notify.core.NotificationType;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Owns {@code !modcall}: files a report through this client's own report channel.
 *
 * <p>Everything happens client side. The report is posted as an embed card to the webhook the user
 * configured in {@code webhook-relay}; no server command is sent and nothing is said in chat. That
 * is deliberate: {@code /report} is not a vanilla command, and the old implementation's
 * {@code /msg <moderator>} reached exactly one named player, who was frequently offline.
 *
 * <p>Every refusal is reported to the caller. A call that quietly did nothing is worse than one
 * that says why it could not be filed, because the player believes a moderator has been told when
 * nobody has.
 */
public class ModCallModule extends AutomationModule {
    /** The command the module answers. */
    public static final String COMMAND = "modcall";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Kind> kind = sgGeneral.add(new EnumSetting.Builder<Kind>()
        .name("kind")
        .description("How the report is labelled in the reports channel.")
        .defaultValue(Kind.MOD)
        .build()
    );

    private final Setting<Boolean> allowRemote = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-remote")
        .description("Let other players file a report through this client. Off by default: "
            + "otherwise a stranger's words appear in your reports channel under your name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> feedback = sgGeneral.add(new BoolSetting.Builder()
        .name("feedback")
        .description("Confirm locally when a report was queued.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Shortest gap between two reports from the same player, so one person cannot "
            + "flood the reports channel.")
        .defaultValue(60)
        .min(0)
        .max(86_400)
        .sliderRange(0, 600)
        .build()
    );

    private final Map<String, Long> lastCallAt = new HashMap<>();

    public ModCallModule() {
        super("modcall",
            "Files !modcall <reason> to your reports channel, client side.",
            "callmod", "modhelp");
    }

    /** How a report is labelled in the channel. */
    public enum Kind {
        /** A request for a moderator to attend. */
        MOD("mod", ModCall.KIND_MOD),
        /** A report about a player or an incident. */
        REPORT("report", ModCall.KIND_REPORT);

        private final String label;
        private final String wire;

        Kind(String label, String wire) {
            this.label = label;
            this.wire = wire;
        }

        /** The name shown in the settings UI. */
        public String label() {
            return label;
        }

        /** The value the embed builder expects. */
        public String wire() {
            return wire;
        }
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        // Runs inside Meteor's dispatch: an unguarded throw would break ordinary chatting.
        SafeEventHandler.run("ModCallModule.onSendMessage", () -> sendStep(event));
    }

    private void sendStep(SendMessageEvent event) {
        if (!isActive() || event == null) return;
        // The assistant answers modcall too, when it is enabled. Cancellation is the arbiter, so
        // only the listener that gets there first files the report.
        if (event.isCancelled()) return;
        String message = event.message;
        if (message == null) return;
        String prefix = commandPrefix();
        String trimmed = message.strip();
        if (!trimmed.startsWith(prefix + COMMAND)) return;

        String reason = trimmed.substring((prefix + COMMAND).length()).strip();
        if (!handle(reason)) return;
        event.cancel();
    }

    /**
     * Files the report, or explains why it could not be filed.
     *
     * @return true when the line was a modcall command, whether or not it succeeded
     */
    private boolean handle(String reason) {
        String sender = localName();
        // Hard local-only gate, independent of the allow-remote setting: this posts to the local
        // user's own reports channel, so it must never be reachable from someone else's chat line.
        if (!allowRemote.get() && !isLocalSpeaker(sender)) {
            return true;
        }
        ModCall.Report report = ModCall.build(sender, reason, commandPrefix(), kind.get().wire());
        if (!report.ok()) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                report.problem().message(commandPrefix()));
            return true;
        }
        if (blocked(sender)) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                "You filed a report recently. Wait " + cooldownSeconds.get()
                    + "s before filing again.");
            return true;
        }
        WebhookRelayModule relay = Modules.get().get(WebhookRelayModule.class);
        if (relay == null || !relay.reportsAllowed()) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                "Reports are not configured. Enable reports on the webhook-relay module and set "
                    + "its URL.");
            return true;
        }
        boolean queued = relay.sendReport(report.reporter(), report.reason(), report.kind(),
            (success, message) -> {
                if (!success) {
                    notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                        "The report could not be delivered: " + message);
                }
            });
        if (!queued) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                "The report could not be queued.");
            return true;
        }
        // Recorded only after the queue accepts it: charging the cooldown for a report that never
        // left would lock the player out of retrying something they never sent.
        lastCallAt.put(key(sender), System.currentTimeMillis());
        if (feedback.get()) {
            info("Report filed to the reports channel.");
        }
        return true;
    }

    /** True when {@code sender} filed a report too recently to file another. */
    private boolean blocked(String sender) {
        int seconds = cooldownSeconds.get();
        if (seconds <= 0) return false;
        Long last = lastCallAt.get(key(sender));
        return last != null && System.currentTimeMillis() - last < seconds * 1000L;
    }

    private static String key(String sender) {
        return sender == null ? "" : sender.strip().toLowerCase(Locale.ROOT);
    }

    private boolean isLocalSpeaker(String sender) {
        String mine = localName();
        if (mine.isBlank() || sender == null || sender.isBlank()) return false;
        return mine.equalsIgnoreCase(sender.strip());
    }

    private String localName() {
        if (mc.player == null) return "";
        var profile = mc.player.getGameProfile();
        return profile == null || profile.getName() == null ? "" : profile.getName();
    }

    /** The assistant's configured prefix, so both features spell the command the same way. */
    private String commandPrefix() {
        AssistantModule assistant = Modules.get().get(AssistantModule.class);
        String prefix = assistant == null ? "!" : assistant.config().commandPrefix();
        return prefix == null || prefix.isBlank() ? "!" : prefix;
    }
}
