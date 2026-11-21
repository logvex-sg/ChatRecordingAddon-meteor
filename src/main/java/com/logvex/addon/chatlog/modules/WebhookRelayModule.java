package com.logvex.addon.chatlog.modules;

import com.logvex.addon.framework.systems.modules.AddonModule;
import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.EmbedAccent;
import com.logvex.addon.chatlog.core.FeatureWebhook;
import com.logvex.addon.chatlog.core.ReportLog;
import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.chatlog.core.WebhookEmbeds;
import com.logvex.addon.chatlog.core.WebhookQueue;
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
import java.util.List;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
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
    private final SettingGroup sgReports = settings.createGroup("Report log");

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

    private final Setting<List<String>> featureWebhooks = sgTarget.add(new StringListSetting.Builder()
        .name("feature-webhooks")
        .description("Send one feature to its own Discord webhook. One per line: "
            + "keywords=URL, macros=URL, modcall=URL, help=URL, status=URL. "
            + "Unset features use the URL above.")
        .defaultValue(List.of())
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

    private final Setting<String> adminWebhookUrl = sgEvents.add(new StringSetting.Builder()
        .name("admin-webhook-url")
        .description("A separate webhook for figures about you: position, gear and targets. "
            + "Leave blank to keep those local. Never post them to the public webhook.")
        .defaultValue("")
        .wide()
        .build()
    );

    private final Setting<Boolean> allowReports = sgEvents.add(new BoolSetting.Builder()
        .name("allow-reports")
        .description("Let !mod and !report post a report card and ping the moderator role.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowMacroCards = sgEvents.add(new BoolSetting.Builder()
        .name("allow-macro-cards")
        .description("Post a card for each macro run. Off keeps macro runs out of the webhook.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> moderatorRoleId = sgEvents.add(new StringSetting.Builder()
        .name("moderator-role-id")
        .description("Discord role to ping on a report: a role id, or plain text to show instead.")
        .defaultValue("")
        .visible(allowReports::get)
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
        // Apply the cap before loading so a config value loaded after construction, or a changed
        // setting, is honoured on the first activate rather than only when it changes.
        reportLog.setCapacity(reportLogSize.get());
        if (reportLogPersist.get()) reportLog.loadAll(reportLogStored.get());
    }

    @Override
    public void onDeactivate() {
        service.setRelayTarget("", "");
        service.setRelayRoutes(new FeatureWebhook("", List.of()));
        service.setEmbedStyle(WebhookEmbeds.Style.defaults());
        service.removeSessionListener(sessionListener);
        identities.removeListener(renameListener);
        service.removeListener(presenceListener);
    }

    private void publishTarget() {
        boolean on = isActive() && enabled.get();
        service.setRelayTarget(on ? url() : "", webhookUsername.get());
        // The routes are published with the target: a sender reads the route for its own feature, so
        // a feature-only override has to reach it even when the shared URL is blank.
        service.setRelayRoutes(on ? routes() : new FeatureWebhook("", List.of()));
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

    /**
     * The routing table: the shared URL plus any per-feature override.
     *
     * <p>Rebuilt on read so a change to either setting takes effect without a restart, and so the
     * caller never holds a stale copy of a URL that may be a secret.
     */
    public FeatureWebhook routes() {
        return new FeatureWebhook(url(), featureWebhooks.get());
    }

    /** The URL for one feature, honouring its override. */
    public String urlFor(FeatureWebhook.Feature feature) {
        return routes().url(feature);
    }

    public String username() {
        return webhookUsername.get() == null ? "" : webhookUsername.get();
    }

    public boolean configured() {
        return !url().isBlank();
    }

    // ---------------------------------------------------------------- report log

    /** Every report this client filed, so a repeat report or a failed one is still visible. */
    private final ReportLog reportLog = new ReportLog();

    private final Setting<Integer> reportLogSize = sgReports.add(new IntSetting.Builder()
        .name("report-log-size")
        .description("How many filed reports to remember. The oldest is dropped past this many.")
        .defaultValue(ReportLog.DEFAULT_CAPACITY)
        .min(10)
        .sliderRange(10, 1000)
        .onChanged(reportLog::setCapacity)
        .build()
    );

    private final Setting<Boolean> reportLogPersist = sgReports.add(new BoolSetting.Builder()
        .name("persist-report-log")
        .description("Keep the report log across restarts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> reportLogStored = sgReports.add(new StringListSetting.Builder()
        .name("reports")
        .description("Saved reports, managed by the module. Editing this by hand is not recommended.")
        .defaultValue(List.of())
        .visible(reportLogPersist::get)
        .build()
    );

    /** The log of reports this client filed. */
    public ReportLog reportLog() {
        return reportLog;
    }

    /** Records a filed report and returns the record, so its outcome can be settled later. */
    private ReportLog.Record recordReport(String reporter, String reason, String kind,
                                          boolean delivered, String note) {
        ReportLog.Record record = new ReportLog.Record(System.currentTimeMillis(), reporter, reason,
            kind, delivered, note);
        reportLog.add(record);
        persistReportLog();
        return record;
    }

    /**
     * Settles a record with the outcome the network worker reported, then persists.
     *
     * <p>Runs on the client thread: the callback arrives on the webhook daemon, and a Meteor
     * setting written from there races the config save.
     */
    private void settleReport(ReportLog.Record record, boolean delivered, String note) {
        if (record == null) return;
        mc.execute(() -> {
            if (reportLog.settle(record, delivered, note)) persistReportLog();
        });
    }

    private void persistReportLog() {
        if (reportLogPersist.get()) reportLogStored.set(reportLog.encodeAll());
    }

    /** True when reports may be posted through this webhook. */
    public boolean reportsAllowed() {
        // Checked against the modcall route, not the shared URL: a user may point reports at their
        // own channel and leave the shared URL blank.
        return allowReports.get() && enabled.get()
            && routes().configured(FeatureWebhook.Feature.MODCALL);
    }

    /** True when macro-run cards may be posted. */
    public boolean macroCardsAllowed() {
        return allowMacroCards.get() && enabled.get()
            && routes().configured(FeatureWebhook.Feature.MACROS);
    }

    /**
     * Posts a macro-run card to the macros route.
     *
     * <p>The URL comes from the routing table, so a user who keeps macro runs in a separate channel
     * gets that channel without a second module. Nothing is sent unless the user opted in.
     */
    public boolean sendMacroRun(String shortcut, String sender, String command, boolean remote,
                                WebhookQueue.Listener listener) {
        if (!macroCardsAllowed()) return false;
        return service.notifyMacroRun(
            urlFor(FeatureWebhook.Feature.MACROS), username(),
            shortcut, sender, command, remote, listener);
    }

    /** The role id (or plain text) to ping on a report; may be blank. */
    public String moderatorRole() {
        return moderatorRoleId.get() == null ? "" : moderatorRoleId.get().strip();
    }

    /** The admin-only webhook URL, or blank when none is set. */
    public String adminUrl() {
        return adminWebhookUrl.get() == null ? "" : adminWebhookUrl.get().strip();
    }

    /** True when a status card may be posted to the admin webhook. */
    public boolean adminStatusAllowed() {
        return enabled.get() && !adminUrl().isBlank();
    }

    /**
     * Posts a status card to the admin webhook.
     *
     * <p>This is a second, separate target on purpose. The public webhook may be visible to a whole
     * Discord server, so a figure such as the local player's coordinates must never go there. A user
     * who wants those in a private channel sets this URL and nothing else changes.
     */
    public boolean sendAdminStatus(com.logvex.addon.stats.ServerStats stats,
                                   WebhookQueue.Listener listener) {
        if (!adminStatusAllowed() || stats == null) return false;
        return service.notifyStatus(adminUrl(), username(), stats, listener);
    }

    /**
     * Posts a report card, pinging the configured moderator role.
     *
     * <p>Every attempt is written to the report log, including one that could not be posted: a
     * report the user believes was filed but was not is the case most worth keeping.
     */
    public boolean sendReport(String reporter, String reason, String kind,
                              WebhookQueue.Listener listener) {
        if (!reportsAllowed()) {
            recordReport(reporter, reason, kind, false, "reports not allowed");
            return false;
        }
        // Written before queueing, so the record exists even if the queue invokes the listener
        // immediately; a report that was attempted must never be missing from the log.
        ReportLog.Record record = recordReport(reporter, reason, kind, false, "queued");
        boolean queued = service.notifyReport(
            urlFor(FeatureWebhook.Feature.MODCALL), username(),
            reporter, reason, moderatorRole(),
            kind, (success, message) -> {
                // Delivery happens on the network worker, after this method has returned, so the
                // log is settled here with what really happened rather than with "queued".
                settleReport(record, success, message);
                if (listener != null) listener.onResult(success, message);
            });
        if (!queued) settleReport(record, false, "could not be queued");
        return queued;
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
            // No early return: a watched player's death or announcement still deserves its own
            // card, and returning here silently dropped both.
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
        ChatDigest.Summary summary = fetchSummary();
        if (summary.isEmpty()) return;
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

    /**
     * The summary of the current window, without reporting anything.
     *
     * <p>Prefers the digest module's own figures so an on-demand card and the timed digest cannot
     * disagree. Silent because the timer path runs unattended.
     */
    private ChatDigest.Summary fetchSummary() {
        ChatDigestModule digest = Modules.get().get(ChatDigestModule.class);
        return digest != null ? digest.summary() : ChatDigest.summarise(service.recent(200), 5);
    }

    /**
     * The summary of the current window, or null when there is nothing to send.
     *
     * <p>Reports why it is empty, so each caller does not repeat the two warnings.
     */
    private ChatDigest.Summary currentSummary() {
        if (!configured()) {
            warning("Set a webhook URL first.");
            return null;
        }
        ChatDigest.Summary summary = fetchSummary();
        if (summary.isEmpty()) {
            warning("No messages to summarise yet.");
            return null;
        }
        return summary;
    }

    /** Sends the scheduled-style digest immediately, without waiting for the timer. */
    public void sendDigest() {
        ChatDigest.Summary summary = currentSummary();
        if (summary == null) return;
        service.notifyDigest(url(), username(), summary, (success, message) -> {
            if (success) info("Digest embed sent.");
            else warning("Digest send failed: %s", message);
        });
    }

    /**
     * Sends a chat-summary report on demand.
     *
     * <p>This is the "send it now" path: the summary of the current window, attributed to the
     * requester, delivered as one card. The timer digest stays separate, so an operator can ask for
     * a report without changing the schedule.
     *
     * @param requestedBy who asked, or blank when triggered from the module widget
     * @param note        a short line for the card, or blank
     */
    public void sendSummary(String requestedBy, String note) {
        ChatDigest.Summary summary = currentSummary();
        if (summary == null) return;
        boolean queued = service.notifySummaryReport(url(), username(), summary, requestedBy, note,
            (success, message) -> {
                if (success) info("Chat summary report sent.");
                else warning("Chat summary send failed: %s", message);
            });
        if (!queued) warning("The summary report could not be queued.");
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

    /** Sends the current server figures as a status embed. */
    public void sendStatus() {
        if (!configured()) {
            warning("Set a webhook URL first.");
            return;
        }
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        com.logvex.addon.stats.ServerStats stats =
            com.logvex.addon.stats.McServerStats.snapshot(mc, System.currentTimeMillis(),
                com.logvex.addon.LogClient.sessionStartMs());
        if (stats.offline()) {
            warning("Join a world first so there is something to report.");
            return;
        }
        service.notifyStatus(url(), username(), stats, (success, message) -> {
            if (success) info("Server status embed sent.");
            else warning("Status send failed: %s", message);
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

        WHorizontalList digestRow = list.add(theme.horizontalList()).expandX().widget();
        WButton sendDigest = digestRow.add(theme.button("Send Digest")).expandX().widget();
        sendDigest.action = this::sendDigest;
        WButton sendSummary = digestRow.add(theme.button("Summary Report")).expandX().widget();
        sendSummary.action = () -> sendSummary("", "");

        WHorizontalList extra = list.add(theme.horizontalList()).expandX().widget();
        WButton leaderboard = extra.add(theme.button("Leaderboard")).expandX().widget();
        leaderboard.action = this::sendLeaderboard;
        WButton profile = extra.add(theme.button("Profile...")).expandX().widget();
        profile.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.PlayerProfileScreen(this));
        WButton status = extra.add(theme.button("Status")).expandX().widget();
        status.action = this::sendStatus;

        list.add(theme.label(configured()
            ? "Target: " + Messages.truncate(com.logvex.addon.chatlog.core.WebhookDeliveryLog.hostOf(url()), 40)
            : "No webhook configured yet.")).expandX().widget();

        return list;
    }
}
