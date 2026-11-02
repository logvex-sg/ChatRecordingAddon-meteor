package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.LogExporter;
import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.chatlog.gui.LogDashboardScreen;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Records chat to disk.
 *
 * <p>This module owns the recording configuration and the storage lifecycle. Every other chat-log
 * module reads the shared {@link LogService} runtime it publishes.
 */
public class ChatLoggerModule extends Module {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault());

    /** Upper bound on messages held for the next webhook report. */
    private static final int REPORT_BUFFER_LIMIT = 2000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCategories = settings.createGroup("Categories");
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final SettingGroup sgRetention = settings.createGroup("Retention");
    private final SettingGroup sgRelay = settings.createGroup("Webhook Reports");

    // General

    private final Setting<Boolean> recording = sgGeneral.add(new BoolSetting.Builder()
        .name("recording")
        .description("Record incoming chat to disk.")
        .defaultValue(true)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print recording status changes to chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> duplicateDetection = sgGeneral.add(new BoolSetting.Builder()
        .name("duplicate-detection")
        .description("Ignore a line that repeats the same sender and text within the window.")
        .defaultValue(true)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<Integer> duplicateWindow = sgGeneral.add(new IntSetting.Builder()
        .name("duplicate-window-ms")
        .description("How close two identical lines must be to count as a duplicate.")
        .defaultValue(250)
        .min(0)
        .sliderRange(0, 5000)
        .visible(duplicateDetection::get)
        .onChanged(value -> syncRuntime())
        .build()
    );

    // Categories

    private final Setting<Boolean> recordChat = sgCategories.add(new BoolSetting.Builder()
        .name("public-chat").description("Record public chat messages.").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordPm = sgCategories.add(new BoolSetting.Builder()
        .name("player-messages").description("Record direct messages.").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordSystem = sgCategories.add(new BoolSetting.Builder()
        .name("system").description("Record system messages.").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordJoin = sgCategories.add(new BoolSetting.Builder()
        .name("joins").description("Record join notifications.").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordLeave = sgCategories.add(new BoolSetting.Builder()
        .name("leaves").description("Record leave notifications.").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordDeath = sgCategories.add(new BoolSetting.Builder()
        .name("deaths").description("Record death messages.").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordAnnouncement = sgCategories.add(new BoolSetting.Builder()
        .name("announcements").description("Record server announcements.").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordGame = sgCategories.add(new BoolSetting.Builder()
        .name("game").description("Record game notifications (advancements, mode changes).").defaultValue(true).onChanged(value -> syncRuntime()).build());
    private final Setting<Boolean> recordOther = sgCategories.add(new BoolSetting.Builder()
        .name("other").description("Record anything that does not fit another category.").defaultValue(false).onChanged(value -> syncRuntime()).build());

    // Storage

    private final Setting<Boolean> saveLogs = sgStorage.add(new BoolSetting.Builder()
        .name("save-logs")
        .description("Persist recorded chat to disk.")
        .defaultValue(true)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<String> storageDirectory = sgStorage.add(new StringSetting.Builder()
        .name("storage-directory")
        .description("Folder for the logs, relative to the Meteor folder.")
        .defaultValue("logaddon/logs")
        .wide()
        .visible(saveLogs::get)
        .build()
    );

    private final Setting<Integer> compressionLevel = sgStorage.add(new IntSetting.Builder()
        .name("compression-level")
        .description("GZIP level for stored segments (1 fastest, 9 smallest).")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 9)
        .visible(saveLogs::get)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<Integer> flushThreshold = sgStorage.add(new IntSetting.Builder()
        .name("flush-threshold")
        .description("Entries buffered before a segment is written to disk.")
        .defaultValue(250)
        .min(10)
        .sliderRange(10, 2000)
        .visible(saveLogs::get)
        .onChanged(value -> syncRuntime())
        .build()
    );

    // Retention

    private final Setting<Boolean> autoCleanup = sgRetention.add(new BoolSetting.Builder()
        .name("auto-cleanup")
        .description("Enforce the retention limits automatically after each flush.")
        .defaultValue(true)
        .visible(saveLogs::get)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<Integer> maxAgeDays = sgRetention.add(new IntSetting.Builder()
        .name("max-age-days")
        .description("Delete log segments older than this. 0 disables the age limit.")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 365)
        .visible(saveLogs::get)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<Integer> maxSizeMb = sgRetention.add(new IntSetting.Builder()
        .name("max-size-mb")
        .description("Maximum total size of the stored logs. Oldest segments are removed first.")
        .defaultValue(100)
        .min(1)
        .sliderRange(1, 2000)
        .visible(saveLogs::get)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<Integer> maxSegments = sgRetention.add(new IntSetting.Builder()
        .name("max-segments")
        .description("Maximum number of stored segments. 0 disables the count limit.")
        .defaultValue(2000)
        .min(0)
        .sliderRange(0, 20000)
        .visible(saveLogs::get)
        .onChanged(value -> syncRuntime())
        .build()
    );

    private final Setting<LogExporter.Format> exportFormat = sgRetention.add(new EnumSetting.Builder<LogExporter.Format>()
        .name("export-format")
        .description("Default format used by the export controls.")
        .defaultValue(LogExporter.Format.TXT)
        .build()
    );

    // Relay (webhook reports)

    private final Setting<String> webhookUrl = sgRelay.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL used by Send Now and periodic reports. Never written to saved logs.")
        .defaultValue("")
        .wide()
        .build()
    );

    private final Setting<String> webhookUsername = sgRelay.add(new StringSetting.Builder()
        .name("webhook-username")
        .description("Display name used for webhook reports.")
        .defaultValue("Log Addon")
        .build()
    );

    private final Setting<Boolean> autoReports = sgRelay.add(new BoolSetting.Builder()
        .name("auto-reports")
        .description("Send a report to the webhook on a fixed interval.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> reportIntervalMinutes = sgRelay.add(new IntSetting.Builder()
        .name("report-interval-minutes")
        .description("Minutes between automatic reports.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 60)
        .visible(autoReports::get)
        .build()
    );

    // Runtime

    private final LogService service = LogService.get();

    /** Messages collected since the last successful report. */
    private final com.logvex.addon.chatlog.core.ReportBuffer reportBuffer = new com.logvex.addon.chatlog.core.ReportBuffer(REPORT_BUFFER_LIMIT);
    private volatile boolean reportInFlight;
    private long lastReportAt;

    public ChatLoggerModule() {
        super(LogAddon.CATEGORY, "chat-logger", "Records chat to disk for search, export and stats.");
    }

    @Override
    public void onActivate() {
        service.attach(new LogStorageService(storagePath()));
        service.setRecording(recording.get());
        service.setRecordedTypes(categories());
        service.setDuplicateWindow(duplicateDetection.get() ? duplicateWindow.get() : 0);
        if (service.session() == null) service.startSession(currentServer(), System.currentTimeMillis());

        reportBuffer.clear();
        lastReportAt = System.currentTimeMillis();
        service.addListener(reportListener);

        LogStorageService storage = service.storage();
        storage.setCompressionLevel(compressionLevel.get());
        storage.setFlushThreshold(flushThreshold.get());
        storage.loadAsync(this::cleanupIfNeeded);
    }

    @Override
    public void onDeactivate() {
        service.removeListener(reportListener);
        service.setRecording(false);
        LogStorageService storage = service.storage();
        if (storage != null) storage.flushAsync(null);
    }

    private final LogService.LiveListener reportListener = reportBuffer::add;

    @meteordevelopment.orbit.EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        if (!autoReports.get() || reportInFlight) return;
        long interval = reportIntervalMinutes.get() * 60_000L;
        if (System.currentTimeMillis() - lastReportAt >= interval) sendReport(false);
    }

    /**
     * Sends the buffered messages to the webhook. Messages are removed from the buffer only after the
     * delivery is confirmed, so a failed request never loses chat. When {@code manual} is false and
     * the buffer is empty, nothing is sent.
     */
    public void sendReport(boolean manual) {
        if (reportInFlight) {
            feedback(false, "A report is already being sent.");
            return;
        }
        List<com.logvex.addon.chatlog.core.ChatEntry> batch = reportBuffer.snapshot();
        if (batch.isEmpty()) {
            if (manual) feedback(false, "No buffered messages to report.");
            lastReportAt = System.currentTimeMillis();
            return;
        }
        if (webhookUrl.get() == null || webhookUrl.get().isBlank()) {
            if (manual) feedback(false, "No webhook URL configured.");
            return;
        }

        reportInFlight = true;
        lastReportAt = System.currentTimeMillis();
        boolean queued = service.notifySummary(webhookUrl.get(), webhookUsername.get(), batch, (success, message) ->
            // The delivery callback runs on the webhook worker; hop back to the client thread.
            onClient(() -> {
                reportInFlight = false;
                if (success) {
                    // Entries that arrived while the request was in flight were appended at the back,
                    // so removing the first batch.size() entries leaves them buffered.
                    reportBuffer.confirmDelivered(batch.size());
                    feedback(true, "Report of " + batch.size() + " messages delivered.");
                } else {
                    feedback(false, "Report delivery failed: " + message + " (messages kept for retry).");
                }
            }));
        if (!queued) {
            reportInFlight = false;
            feedback(false, "Report could not be queued.");
        }
    }

    private void onClient(Runnable task) {
        var client = MeteorClient.mc;
        if (client == null) {
            task.run();
            return;
        }
        client.execute(task);
    }

    public boolean reportInFlight() {
        return reportInFlight;
    }

    public int reportBufferSize() {
        return reportBuffer.size();
    }

    @Override
    public String getInfoString() {
        LogStorageService storage = service.storage();
        return storage == null ? null : storage.store().totalEntries() + " logged";
    }

    // ------------------------------------------------------------------ accessors

    public LogService service() {
        return service;
    }

    public LogStorageService storage() {
        return service.storage();
    }

    public LogExporter.Format defaultExportFormat() {
        return exportFormat.get();
    }

    public Set<MessageType> categories() {
        Set<MessageType> types = EnumSet.noneOf(MessageType.class);
        if (recordChat.get()) types.add(MessageType.PUBLIC_CHAT);
        if (recordPm.get()) types.add(MessageType.PLAYER_MESSAGE);
        if (recordSystem.get()) types.add(MessageType.SYSTEM);
        if (recordJoin.get()) types.add(MessageType.JOIN);
        if (recordLeave.get()) types.add(MessageType.LEAVE);
        if (recordDeath.get()) types.add(MessageType.DEATH);
        if (recordAnnouncement.get()) types.add(MessageType.ANNOUNCEMENT);
        if (recordGame.get()) types.add(MessageType.GAME);
        if (recordOther.get()) types.add(MessageType.OTHER);
        return types;
    }

    public Path storagePath() {
        String configured = storageDirectory.get();
        String relative = configured == null || configured.isBlank() ? "logaddon/logs" : configured;
        return MeteorClient.FOLDER.toPath().resolve(relative);
    }

    // ------------------------------------------------------------------ controls

    /** Mirrors the live settings into the runtime. Safe to call before the module is active. */
    private void syncRuntime() {
        if (!isActive()) return;
        service.setRecording(recording.get() && saveLogs.get());
        service.setRecordedTypes(categories());
        service.setDuplicateWindow(duplicateDetection.get() ? duplicateWindow.get() : 0);
        LogStorageService storage = service.storage();
        if (storage == null) return;
        storage.setCompressionLevel(compressionLevel.get());
        storage.setFlushThreshold(flushThreshold.get());
        if (autoCleanup.get()) runCleanup();
    }

    /** Re-reads the settings that the runtime mirrors and applies retention. */
    public void applySettings() {
        syncRuntime();
    }

    public void startRecording() {
        service.setRecording(true);
        service.setRecordedTypes(categories());
        feedback(true, "Recording started.");
    }

    public void stopRecording() {
        service.setRecording(false);
        service.flush(null);
        feedback(true, "Recording stopped.");
    }

    public boolean isRecording() {
        return service.isRecording();
    }

    public void rescanStorage() {
        LogStorageService storage = service.storage();
        if (storage == null) return;
        storage.rebuildIndexAsync(() -> feedback(true, "Log index rebuilt."));
    }

    public void runCleanup() {
        LogStorageService storage = service.storage();
        if (storage == null) return;
        storage.cleanupAsync(maxSegments.get(), maxBytes(), maxAgeMs(), null);
    }

    private void cleanupIfNeeded() {
        if (!autoCleanup.get()) return;
        runCleanup();
    }

    public long maxBytes() {
        return (long) maxSizeMb.get() * 1024L * 1024L;
    }

    public int maxAgeDays() {
        return maxAgeDays.get();
    }

    public int maxSegmentsSetting() {
        return maxSegments.get();
    }

    public long maxAgeMs() {
        int days = maxAgeDays.get();
        return days <= 0 ? 0 : days * 86_400_000L;
    }

    /** Exports the current session to the configured format. */
    public void exportSession() {
        LogStorageService storage = service.storage();
        if (storage == null) {
            feedback(false, "Storage is unavailable.");
            return;
        }
        if (service.session() == null) {
            feedback(false, "There is no active session to export.");
            return;
        }
        LogQuery query = LogQuery.builder().session(service.session().id()).build();
        String fileName = exportFileName("chatlog-session") + "." + exportFormat.get().extension();
        Path destination = storage.root().resolve("exports").resolve(fileName);
        storage.exportAsync(query, destination, exportFormat.get(),
            count -> feedback(true, "Exported " + count + " messages to " + destination),
            error -> feedback(false, "Export failed: " + error));
    }

    /** Exports every stored entry. */
    public void exportAll() {
        LogStorageService storage = service.storage();
        if (storage == null) {
            feedback(false, "Storage is unavailable.");
            return;
        }
        String fileName = exportFileName("chatlog-all") + "." + exportFormat.get().extension();
        Path destination = storage.root().resolve("exports").resolve(fileName);
        storage.exportAsync(LogQuery.all(), destination, exportFormat.get(),
            count -> feedback(true, "Exported " + count + " messages to " + destination),
            error -> feedback(false, "Export failed: " + error));
    }

    public void openDashboard() {
        mc.setScreen(new LogDashboardScreen(this));
    }

    // ------------------------------------------------------------------ helpers

    private String currentServer() {
        if (mc.getCurrentServerEntry() != null) {
            String address = mc.getCurrentServerEntry().address;
            if (address != null && !address.isBlank()) return address;
        }
        return mc.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    public void feedback(boolean success, String message) {
        if (!chatFeedback.get()) return;
        Formatting color = success ? Formatting.GREEN : Formatting.RED;
        warning("%s", color + "[Log Addon] " + message);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WButton dashboard = list.add(theme.button("Open Log Addon Dashboard")).expandX().widget();
        dashboard.action = this::openDashboard;

        WHorizontalList controls = list.add(theme.horizontalList()).expandX().widget();

        WButton toggle = controls.add(theme.button(isRecording() ? "Stop Recording" : "Start Recording")).expandX().widget();
        toggle.action = () -> {
            if (isRecording()) stopRecording();
            else startRecording();
        };

        WButton cleanup = controls.add(theme.button("Cleanup")).expandX().widget();
        cleanup.action = this::runCleanup;

        // Send Now / View Saved Reports / Archive Settings
        WHorizontalList reportControls = list.add(theme.horizontalList()).expandX().widget();

        WButton sendNow = reportControls.add(theme.button("Send Now")).expandX().widget();
        sendNow.action = () -> sendReport(true);

        WButton viewReports = reportControls.add(theme.button("View Saved Reports")).expandX().widget();
        viewReports.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.StorageScreen(this));

        WButton archiveSettings = reportControls.add(theme.button("Archive Settings")).expandX().widget();
        archiveSettings.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.StorageScreen(this));

        return list;
    }

    /** Renders a compact storage summary used by the dashboard. */
    public String storageSummary() {
        LogStorageService storage = service.storage();
        if (storage == null) return "storage unavailable";
        return storage.store().segmentCount() + " segments, " + ReportFormatting.bytes(storage.store().totalCompressedBytes());
    }

    public LogQuery currentSessionQuery() {
        LogService svc = service;
        return svc.session() == null ? LogQuery.all() : LogQuery.builder().session(svc.session().id()).build();
    }

    public String exportFileName(String prefix) {
        return prefix + "-" + STAMP.format(Instant.now());
    }
}
