package com.logvex.addon.chatlog.modules;

import com.logvex.addon.framework.systems.modules.AddonModule;
import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.EmbedAccent;
import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import com.logvex.addon.chatlog.gui.EmbedPreviewScreen;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.mc.PlayerIdentityService;
import com.logvex.addon.common.Messages;
import meteordevelopment.meteorclient.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.core.NotificationType;
import com.logvex.addon.notify.mc.NotificationService;

/**
 * The one place a webhook is configured, and the one place its output is styled.
 *
 * <p>Before this module every feature carried its own URL setting and sent a flat {@code content}
 * string. Now the relay owns the URL, the display name and the embed appearance, publishes them to
 * {@link LogService}, and every other module (keyword alerts, periodic reports, digests) reads the
 * shared target. The output is a Discord rich embed, so a card is a titled, coloured, fielded
 * message rather than a paste.
 *
 * <p>The relay can also raise cards for events that previously only appeared in chat: a finished
 * recording session, an observed rename, and a watchlist join or leave. Each is opt-in.
 */
public class WebhookRelayModule extends AddonModule {
    private final SettingGroup sgTarget = settings.getDefaultGroup();
    private final SettingGroup sgAppearance = settings.createGroup("Appearance");
    private final SettingGroup sgEvents = settings.createGroup("Events");
    private final SettingGroup sgDigest = settings.createGroup("Digest");

    private final Setting<String> webhookUrl = sgTarget.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL. Treated as a secret: never written to logs or payloads.")
        .defaultValue("")
        .wide()
        .onChanged(value -> publishTarget())
        .build()
    );

    private final Setting<String> webhookUsername = sgTarget.add(new StringSetting.Builder()
        .name("webhook-username")
        .description("Display name used for webhook messages.")
        .defaultValue("Log Client")
        .onChanged(value -> publishTarget())
        .build()
    );

    private final Setting<Boolean> enabled = sgTarget.add(new BoolSetting.Builder()
        .name("relay-enabled")
        .description("Actually send embeds. Off means the URL is stored but nothing is transmitted.")
        .defaultValue(true)
        .onChanged(value -> publishTarget())
        .build()
    );

    // Appearance

    private final Setting<EmbedAccent> accent = sgAppearance.add(new EnumSetting.Builder<EmbedAccent>()
        .name("accent")
        .description("Embed accent colour. Auto colours each card by its type.")
        .defaultValue(EmbedAccent.AUTO)
        .onChanged(value -> publishStyle())
        .build()
    );

    private final Setting<String> footer = sgAppearance.add(new StringSetting.Builder()
        .name("footer")
        .description("Footer text shown on every embed.")
        .defaultValue("Log Client")
        .onChanged(value -> publishStyle())
        .build()
    );

    private final Setting<String> authorName = sgAppearance.add(new StringSetting.Builder()
        .name("author")
        .description("Author line shown on every embed. Blank hides it.")
        .defaultValue("")
        .onChanged(value -> publishStyle())
        .build()
    );

    private final Setting<Boolean> showTimestamp = sgAppearance.add(new BoolSetting.Builder()
        .name("timestamp")
        .description("Show a timestamp under each embed.")
        .defaultValue(true)
        .onChanged(value -> publishStyle())
        .build()
    );

    private final Setting<Boolean> inlineFields = sgAppearance.add(new BoolSetting.Builder()
        .name("inline-fields")
        .description("Lay short fields out side by side.")
        .defaultValue(true)
        .onChanged(value -> publishStyle())
        .build()
    );

    private final Setting<Boolean> showServer = sgAppearance.add(new BoolSetting.Builder()
        .name("show-server")
        .description("Include the server address on each embed.")
        .defaultValue(true)
        .onChanged(value -> publishStyle())
        .build()
    );

    // Events

    private final Setting<Boolean> onSessionEnd = sgEvents.add(new BoolSetting.Builder()
        .name("session-end")
        .description("Send a summary embed when a recording session ends.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onRename = sgEvents.add(new BoolSetting.Builder()
        .name("player-rename")
        .description("Send an embed when an account is seen under a new name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onPresence = sgEvents.add(new BoolSetting.Builder()
        .name("watchlist-presence")
        .description("Send an embed when a watched player joins or leaves.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onDeaths = sgEvents.add(new BoolSetting.Builder()
        .name("deaths")
        .description("Send an embed for every death message.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onAnnouncements = sgEvents.add(new BoolSetting.Builder()
        .name("announcements")
        .description("Send an embed for server announcements.")
        .defaultValue(false)
        .build()
    );

    // Digest

    private final Setting<Boolean> digestEnabled = sgDigest.add(new BoolSetting.Builder()
        .name("digest")
        .description("Send a chat-digest embed on a timer.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> digestMinutes = sgDigest.add(new IntSetting.Builder()
        .name("digest-interval-minutes")
        .description("Minutes between digest embeds.")
        .defaultValue(30)
        .min(1)
        .sliderRange(1, 240)
        .visible(digestEnabled::get)
        .build()
    );

    private final LogService service = LogService.get();
    private final PlayerIdentityService identities = PlayerIdentityService.get();

    private final LogService.SessionListener sessionListener = this::onSessionEnded;
    private final PlayerIdentityService.RenameListener renameListener = this::onRename;
    private final LogService.LiveListener presenceListener = this::onEntry;

    private long lastDigestAt;

    public WebhookRelayModule() {
        super(LogClient.CATEGORY, "webhook-relay",
            "One webhook, rich embed output, and cards for sessions, renames and watchlist activity.");
    }

    @Override
    public void onActivate() {
        publishTarget();
        publishStyle();
        service.addSessionListener(sessionListener);
        identities.addListener(renameListener);
        service.addListener(presenceListener);
        lastDigestAt = System.currentTimeMillis();
    }

    @Override
    public void onDeactivate() {
        service.setRelayTarget("", "");
        service.setEmbedStyle(WebhookEmbeds.Style.defaults());
        service.removeSessionListener(sessionListener);
        identities.removeListener(renameListener);
        service.removeListener(presenceListener);
    }

    private void publishTarget() {
        boolean on = isActive() && enabled.get();
        service.setRelayTarget(on ? url() : "", webhookUsername.get());
    }

    private void publishStyle() {
        service.setEmbedStyle(style());
    }

    /** The current embed appearance, shared with every other sender. */
    public WebhookEmbeds.Style style() {
        return new WebhookEmbeds.Style(
            accent.get() == null ? "auto" : accent.get().id(),
            footer.get(),
            showTimestamp.get(),
            inlineFields.get(),
            authorName.get(),
            showServer.get());
    }

    public String url() {
        return webhookUrl.get() == null ? "" : webhookUrl.get();
    }

    public String username() {
        return webhookUsername.get() == null ? "" : webhookUsername.get();
    }

    public boolean configured() {
        return !url().isBlank();
    }

    // ------------------------------------------------------------------ events

    private void onSessionEnded(SessionRecord record) {
        if (!isActive() || !onSessionEnd.get() || !configured()) return;
        service.notifySession(url(), username(), record, (success, message) -> {
            if (success) {
                NotificationService.get().notify(NotificationType.GENERAL, "webhook-relay",
                    "Session reported", "Sent the session summary to the webhook.");
            } else {
                NotificationService.get().notify(NotificationType.CONNECTION, NotificationPriority.HIGH,
                    "webhook-relay", "Session report failed", message);
            }
        });
    }

    private void onRename(com.logvex.addon.chatlog.core.PlayerIdentityTracker.Rename rename) {
        if (!isActive() || !onRename.get() || !configured()) return;
        service.notifyRename(url(), username(), rename.from(), rename.to(), rename.atMs());
    }

    private void onEntry(com.logvex.addon.chatlog.core.ChatEntry entry) {
        if (!isActive() || !configured()) return;

        if (onPresence.get()
            && (entry.type() == com.logvex.addon.chatlog.core.MessageType.JOIN
                || entry.type() == com.logvex.addon.chatlog.core.MessageType.LEAVE)
            && !entry.sender().isBlank()
            && service.watchlist().contains(entry.sender())) {
            service.notifyPresence(url(), username(),
                entry.type() == com.logvex.addon.chatlog.core.MessageType.JOIN, entry.sender());
            return;
        }

        if (onDeaths.get() && entry.type() == com.logvex.addon.chatlog.core.MessageType.DEATH) {
            service.notifyDeath(url(), username(), entry);
            return;
        }

        if (onAnnouncements.get() && entry.type() == com.logvex.addon.chatlog.core.MessageType.ANNOUNCEMENT) {
            service.notifyAnnouncement(url(), username(), entry);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!digestEnabled.get() || !isActive()) return;
        long interval = digestMinutes.get() * 60_000L;
        if (System.currentTimeMillis() - lastDigestAt < interval) return;
        lastDigestAt = System.currentTimeMillis();
        if (!configured()) return;
        ChatDigestModule digest = Modules.get().get(ChatDigestModule.class);
        ChatDigest.Summary summary = digest != null ? digest.summary()
            : ChatDigest.summarise(service.recent(200), 5);
        service.notifyDigest(url(), username(), summary, null);
    }

    // ------------------------------------------------------------------ controls

    /** Sends a test embed and reports the outcome in chat. */
    public void test() {
        if (!configured()) {
            warning("Set a webhook URL first.");
            return;
        }
        service.testWebhook(url(), username(), (success, message) -> {
            if (success) info("Webhook test embed delivered.");
            else warning("Webhook test failed: %s", message);
        });
    }

    /** Sends the most active players as a leaderboard embed. */
    public void sendLeaderboard() {
        if (!configured()) {
            warning("Set a webhook URL first.");
            return;
        }
        var players = service.snapshot().topPlayers();
        java.util.List<com.logvex.addon.chatlog.core.ChatDigest.PlayerLine> lines = new java.util.ArrayList<>();
        int rank = 1;
        for (String name : players) {
            com.logvex.addon.chatlog.core.PlayerActivity activity = service.players().get(name);
            long messages = activity == null ? 0 : activity.messageCount();
            lines.add(new com.logvex.addon.chatlog.core.ChatDigest.PlayerLine(name, messages, 0, 0, 0));
            if (rank++ >= 10) break;
        }
        if (lines.isEmpty()) {
            warning("No players to rank yet.");
            return;
        }
        service.notifyLeaderboard(url(), username(), lines, (success, message) -> {
            if (success) info("Leaderboard embed sent.");
            else warning("Leaderboard send failed: %s", message);
        });
    }

    /** Sends one player's observed activity as a profile embed. */
    public void sendProfile(String name) {
        if (!configured()) {
            warning("Set a webhook URL first.");
            return;
        }
        com.logvex.addon.chatlog.core.PlayerActivity activity =
            name == null ? null : service.players().get(name);
        if (activity == null) {
            warning("No activity recorded for %s.", name == null ? "?" : name);
            return;
        }
        service.notifyProfile(url(), username(), activity, (success, message) -> {
            if (success) info("Profile embed sent.");
            else warning("Profile send failed: %s", message);
        });
    }

    @Override
    public String getInfoString() {
        return configured() ? (enabled.get() ? "on" : "muted") : null;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();
        WButton test = row.add(theme.button("Test Embed")).expandX().widget();
        test.action = this::test;
        WButton preview = row.add(theme.button("Preview...")).expandX().widget();
        preview.action = () -> mc.setScreen(new EmbedPreviewScreen(this));

        WButton sendDigest = list.add(theme.button("Send Digest Now")).expandX().widget();
        sendDigest.action = () -> {
            if (!configured()) {
                warning("Set a webhook URL first.");
                return;
            }
            ChatDigestModule digest = Modules.get().get(ChatDigestModule.class);
            ChatDigest.Summary summary = digest != null ? digest.summary()
                : ChatDigest.summarise(service.recent(200), 5);
            if (summary.isEmpty()) {
                warning("No messages to summarise.");
                return;
            }
            service.notifyDigest(url(), username(), summary, (success, message) -> {
                if (success) info("Digest embed sent.");
                else warning("Digest send failed: %s", message);
            });
        };

        WHorizontalList extra = list.add(theme.horizontalList()).expandX().widget();
        WButton leaderboard = extra.add(theme.button("Leaderboard")).expandX().widget();
        leaderboard.action = this::sendLeaderboard;
        WButton profile = extra.add(theme.button("Profile...")).expandX().widget();
        profile.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.PlayerProfileScreen(this));

        list.add(theme.label(configured()
            ? "Target: " + Messages.truncate(com.logvex.addon.chatlog.core.WebhookDeliveryLog.hostOf(url()), 40)
            : "No webhook configured yet.")).expandX().widget();

        return list;
    }
}
