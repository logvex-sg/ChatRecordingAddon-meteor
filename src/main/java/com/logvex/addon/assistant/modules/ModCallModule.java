package com.logvex.addon.assistant.modules;

import com.logvex.addon.assistant.core.ModCall;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.automation.modules.AutomationModule;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.notify.core.NotificationType;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.orbit.EventHandler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Owns {@code !modcall}: sends one private message to a moderator the user configured.
 *
 * <p>This is a module of its own rather than a corner of the assistant for one reason: it makes the
 * client send {@code /msg} from the local player's own account, so it needs a permission story and
 * a switch that stand on their own. With the assistant disabled, this feature is disabled too,
 * which is the behaviour a user expects from a switch labelled "modcall".
 *
 * <p>Every refusal is reported to the speaker. A call that quietly did nothing — the old
 * behaviour, where a missing target produced no visible output at all — is worse than one that
 * says why it could not be sent, because the player believes a moderator has been told when nobody
 * has.
 */
public class ModCallModule extends AutomationModule {
    /** The command the module answers. */
    public static final String COMMAND = "modcall";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> target = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Moderator's exact in-game name. A typo sends the request to nobody, so this "
            + "must be spelled exactly.")
        .defaultValue("")
        .wide()
        .build()
    );

    private final Setting<String> moderatorFormat = sgGeneral.add(new StringSetting.Builder()
        .name("message")
        .description("What the moderator is sent. Tokens: {reason}, {reporter}, {moderator}, {prefix}.")
        .defaultValue(ModCall.DEFAULT_MODERATOR_FORMAT)
        .wide()
        .build()
    );

    private final Setting<String> reporterFormat = sgGeneral.add(new StringSetting.Builder()
        .name("receipt")
        .description("Receipt messaged back to the caller. Leave blank to send no receipt.")
        .defaultValue(ModCall.DEFAULT_REPORTER_FORMAT)
        .wide()
        .build()
    );

    private final Setting<Boolean> allowRemote = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-remote")
        .description("Let other players call a moderator through this client. Off by default: "
            + "otherwise a stranger's words are delivered to a moderator under your name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> feedback = sgGeneral.add(new BoolSetting.Builder()
        .name("feedback")
        .description("Confirm locally when a call was sent.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Shortest gap between two calls from the same player, so one person cannot "
            + "fill a moderator's inbox.")
        .defaultValue(60)
        .min(0)
        .max(86_400)
        .sliderRange(0, 600)
        .build()
    );

    private final Map<String, Long> lastCallAt = new HashMap<>();
    private boolean sending;

    public ModCallModule() {
        super("modcall",
            "Sends !modcall <reason> as a private message to a configured moderator.",
            "callmod", "modhelp");
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        // Runs inside Meteor's dispatch: an unguarded throw would break ordinary chatting.
        SafeEventHandler.run("ModCallModule.onSendMessage", () -> sendStep(event));
    }

    private void sendStep(SendMessageEvent event) {
        if (!isActive() || event == null || sending) return;
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
     * Builds and sends the call, or explains why it could not be sent.
     *
     * @return true when the line was a modcall command, whether or not it succeeded
     */
    private boolean handle(String reason) {
        String sender = localName();
        // Hard local-only gate, independent of the allow-remote setting: this sends from the local
        // player's own account, so it must never be reachable from someone else's chat line.
        if (!allowRemote.get() && !isLocalSpeaker(sender)) {
            return true;
        }
        if (reason.isEmpty()) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                ModCall.Problem.NO_REASON.message(commandPrefix()));
            return true;
        }
        ModCall.Call call = ModCall.build(target.get(), reason, commandPrefix(),
            moderatorFormat.get(), reporterFormat.get(), sender);
        if (!call.ok()) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                call.problem().message(commandPrefix()));
            return true;
        }
        if (blocked(sender)) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                "You called a moderator recently. Wait " + cooldownSeconds.get()
                    + "s before calling again.");
            return true;
        }
        if (mc.getNetworkHandler() == null) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                "Not connected, so the call could not be sent.");
            return true;
        }
        sending = true;
        try {
            for (String command : call.commands()) {
                mc.getNetworkHandler().sendChatCommand(command);
            }
        } catch (RuntimeException failure) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "Modcall",
                "The modcall could not be sent: " + failure.getMessage());
            return true;
        } finally {
            sending = false;
        }
        // Recorded only after the send: charging the cooldown for a call that failed would lock the
        // player out of retrying something they never sent.
        lastCallAt.put(key(sender), System.currentTimeMillis());
        if (feedback.get()) {
            info("Modcall sent to %s.", call.target());
        }
        return true;
    }

    /** True when {@code sender} called too recently to call again. */
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
        var assistant = meteordevelopment.meteorclient.systems.modules.Modules.get()
            .get(AssistantModule.class);
        String prefix = assistant == null ? "!" : assistant.config().commandPrefix();
        return prefix == null || prefix.isBlank() ? "!" : prefix;
    }

    /**
     * True when a target is configured and is a legal player name.
     *
     * <p>Deliberately not called {@code ready}: the base class already owns that name for its own
     * "player and world are present" check, and overriding it would silently break that.
     */
    public boolean configured() {
        return !target.get().isBlank() && SenderIdentity.validName(target.get().strip());
    }
}
