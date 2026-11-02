package com.logvex.addon.chatrelay;

import com.logvex.addon.LogvexAddon;
import com.logvex.addon.chatrelay.core.ChatBuffer;
import com.logvex.addon.chatrelay.core.ChatMessage;
import com.logvex.addon.chatrelay.core.ChatReport;
import com.logvex.addon.chatrelay.core.ReportMetadata;
import com.logvex.addon.chatrelay.core.ReportPayloadBuilder;
import com.logvex.addon.chatrelay.core.WebhookSender;
import com.logvex.addon.chatrelay.gui.ReportBrowserScreen;
import com.logvex.addon.chatrelay.mc.ArchiveService;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Buffers incoming chat and periodically relays it to a Discord webhook, optionally keeping a
 * compressed local archive of every report.
 *
 * <p>Messages are only ever removed from the buffer after a webhook request succeeds, so a failed
 * delivery never loses chat. All disk and network work happens on background threads.
 */
public class LiveChatRelayModule extends Module {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault());

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArchive = settings.createGroup("Archive");

    // General

    private final Setting<String> webhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL reports are posted to. Kept out of saved reports.")
        .defaultValue("")
        .wide()
        .build()
    );

    private final Setting<Boolean> enabledRelay = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Send reports to the webhook.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> intervalMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("interval-minutes")
        .description("Minutes between automatic reports.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<Integer> bufferCapacity = sgGeneral.add(new IntSetting.Builder()
        .name("buffer-capacity")
        .description("Maximum number of chat messages held before the oldest are dropped.")
        .defaultValue(2000)
        .min(100)
        .sliderRange(100, 10000)
        .build()
    );

    private final Setting<String> username = sgGeneral.add(new StringSetting.Builder()
        .name("username")
        .description("Display name used for the webhook message.")
        .defaultValue("LiveChatRelay")
        .build()
    );

    private final Setting<Boolean> includeGameMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("include-game-messages")
        .description("Also capture game/system messages, not just player chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print report delivery results to chat.")
        .defaultValue(true)
        .build()
    );

    // Archive

    private final Setting<Boolean> saveReports = sgArchive.add(new BoolSetting.Builder()
        .name("save-reports")
        .description("Keep a compressed local archive of every report.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> compressionLevel = sgArchive.add(new IntSetting.Builder()
        .name("compression-level")
        .description("GZIP level used for archived reports (1 fastest, 9 smallest).")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 9)
        .visible(saveReports::get)
        .build()
    );

    private final Setting<Integer> maxArchiveMb = sgArchive.add(new IntSetting.Builder()
        .name("max-archive-size-mb")
        .description("Maximum total size of the compressed archive. Oldest reports are removed first.")
        .defaultValue(50)
        .min(1)
        .sliderRange(1, 500)
        .visible(saveReports::get)
        .build()
    );

    private final Setting<Integer> maxArchiveAgeDays = sgArchive.add(new IntSetting.Builder()
        .name("max-archive-age-days")
        .description("Delete archived reports older than this many days. 0 disables the age limit.")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 365)
        .visible(saveReports::get)
        .build()
    );

    private final Setting<Integer> maxReports = sgArchive.add(new IntSetting.Builder()
        .name("max-reports")
        .description("Maximum number of archived reports. 0 disables the count limit.")
        .defaultValue(500)
        .min(0)
        .sliderRange(0, 5000)
        .visible(saveReports::get)
        .build()
    );

    private final Setting<Boolean> autoCleanup = sgArchive.add(new BoolSetting.Builder()
        .name("auto-cleanup")
        .description("Run archive retention automatically after each saved report.")
        .defaultValue(true)
        .visible(saveReports::get)
        .build()
    );

    private final Setting<String> storageDirectory = sgArchive.add(new StringSetting.Builder()
        .name("storage-directory")
        .description("Folder for the archive, relative to the Meteor folder.")
        .defaultValue("chatrelay/archive")
        .wide()
        .visible(saveReports::get)
        .build()
    );

    // Runtime

    private ChatBuffer buffer = new ChatBuffer(2000);
    private final WebhookSender sender = new WebhookSender();
    private ArchiveService archive;

    /** Delivery status waiting to be written once the archive entry exists. */
    private final java.util.Map<String, String> pendingStatus = new java.util.concurrent.ConcurrentHashMap<>();

    private String serverAddress = "";
    private long lastReportAt;
    private String lastDeliveryMessage = "";
    private boolean lastDeliveryOk = true;

    public LiveChatRelayModule() {
        super(LogvexAddon.CATEGORY, "live-chat-relay", "Records chat and sends it to a webhook.");
    }

    @Override
    public void onActivate() {
        buffer = new ChatBuffer(bufferCapacity.get());
        serverAddress = currentServer();
        lastReportAt = System.currentTimeMillis();
        resolveArchive();
        archive.loadIndexAsync(this::cleanupIfNeeded);
    }

    @Override
    public void onDeactivate() {
        if (archive != null) {
            archive.shutdown();
            archive = null;
        }
        buffer.clear();
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (event.getMessage() == null) return;

        boolean isPlayerChat = event.getIndicator() != null;
        if (!isPlayerChat && !includeGameMessages.get()) return;

        String content = event.getMessage().getString();
        if (content == null || content.isBlank()) return;

        String senderName = isPlayerChat ? extractSender(content) : "";
        ChatMessage.Kind kind = isPlayerChat ? ChatMessage.Kind.CHAT : ChatMessage.Kind.GAME;

        buffer.add(new ChatMessage(System.currentTimeMillis(), kind, senderName, content));
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        serverAddress = currentServer();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (buffer.capacity() != bufferCapacity.get()) {
            ChatBuffer replacement = new ChatBuffer(bufferCapacity.get());
            for (ChatMessage message : buffer.drain()) replacement.add(message);
            buffer = replacement;
        }

        if (!enabledRelay.get()) return;

        long intervalMs = intervalMinutes.get() * 60_000L;
        if (System.currentTimeMillis() - lastReportAt < intervalMs) return;
        lastReportAt = System.currentTimeMillis();

        if (buffer.isEmpty()) return;
        generateAndSend("interval");
    }

    // ---------------------------------------------------------------- controls

    /** Generates a report from everything currently buffered and sends it immediately. */
    public void sendNow() {
        if (sender.isBusy()) {
            feedback(false, "A report is already being delivered.");
            return;
        }
        if (buffer.isEmpty()) {
            feedback(false, "There are no buffered messages to send.");
            return;
        }
        lastReportAt = System.currentTimeMillis();
        generateAndSend("manual");
    }

    public void openReportBrowser() {
        if (archive == null) resolveArchive();
        mc.setScreen(new ReportBrowserScreen(archive));
    }

    public boolean isDeliveryBusy() {
        return sender.isBusy();
    }

    public String lastDeliveryMessage() {
        return lastDeliveryMessage;
    }

    public boolean lastDeliveryOk() {
        return lastDeliveryOk;
    }

    public ArchiveService archive() {
        return archive;
    }

    public int bufferedMessages() {
        return buffer.size();
    }

    public Path archiveRoot() {
        return archive == null ? Path.of(".") : archive.root();
    }

    public void reloadArchiveIndex() {
        if (archive != null) archive.loadIndexAsync(null);
    }

    public void runCleanup() {
        if (archive == null || !saveReports.get()) return;
        archive.cleanupAsync(maxReports.get(), maxArchiveBytes(), maxArchiveAgeMs(), null);
    }

    // ---------------------------------------------------------------- internals

    private void generateAndSend(String trigger) {
        List<ChatMessage> messages = buffer.peek();
        if (messages.isEmpty()) return;

        ChatReport report = buildReport(messages);

        if (saveReports.get() && archive != null) {
            // Archive first: the report is preserved even when the webhook then fails.
            archive.writeAsync(report, compressionLevel.get(),
                metadata -> {
                    applyPendingStatus(metadata);
                    cleanupIfNeeded();
                },
                error -> LogvexAddon.LOG.warn("Failed to archive chat report: {}", error));
        }

        if (webhookUrl.get() == null || webhookUrl.get().isBlank()) {
            // Nothing to deliver to; keep the messages buffered so enabling a URL later still works.
            lastDeliveryOk = false;
            lastDeliveryMessage = "No webhook URL configured; report archived locally.";
            feedback(false, lastDeliveryMessage);
            return;
        }

        String payload = ReportPayloadBuilder.discordPayload(report, username.get());
        sender.sendAsync(webhookUrl.get(), payload, (success, message) -> {
            lastDeliveryOk = success;
            lastDeliveryMessage = message;
            if (success) {
                // Only now are the delivered messages removed from the buffer.
                buffer.drain();
                if (saveReports.get() && archive != null) {
                    pendingStatus.put(report.id(), ChatReport.STATUS_DELIVERED);
                    ReportMetadata metadata = findMetadata(report.id());
                    if (metadata != null) applyPendingStatus(metadata);
                }
            }
            feedback(success, message);
        });
    }

    /** Applies a delivery status that arrived before the archive entry was written. */
    private void applyPendingStatus(ReportMetadata metadata) {
        if (metadata == null || archive == null) return;
        String status = pendingStatus.remove(metadata.id());
        if (status == null || status.equals(metadata.deliveryStatus())) return;
        archive.updateStatusAsync(metadata.withStatus(status), null);
    }

    private ReportMetadata findMetadata(String id) {
        for (ReportMetadata metadata : archive.metadata()) {
            if (metadata.id().equals(id)) return metadata;
        }
        return null;
    }

    private void cleanupIfNeeded() {
        if (!saveReports.get() || !autoCleanup.get() || archive == null) return;
        archive.cleanupAsync(maxReports.get(), maxArchiveBytes(), maxArchiveAgeMs(), null);
    }

    private long maxArchiveBytes() {
        return (long) maxArchiveMb.get() * 1024L * 1024L;
    }

    private long maxArchiveAgeMs() {
        int days = maxArchiveAgeDays.get();
        return days <= 0 ? 0 : days * 86_400_000L;
    }

    private ChatReport buildReport(List<ChatMessage> messages) {
        long start = messages.get(0).timestamp();
        long end = messages.get(messages.size() - 1).timestamp();
        long now = System.currentTimeMillis();
        String id = STAMP.format(Instant.ofEpochMilli(now)) + "-" + UUID.randomUUID().toString().substring(0, 6);
        return new ChatReport(id, serverAddress, start, end, now, ChatReport.STATUS_PENDING, List.copyOf(messages));
    }

    private void resolveArchive() {
        if (archive != null) archive.shutdown();
        archive = new ArchiveService(archivePath());
    }

    private Path archivePath() {
        String configured = storageDirectory.get();
        String relative = configured == null || configured.isBlank() ? "chatrelay/archive" : configured;
        return MeteorClient.FOLDER.toPath().resolve(relative);
    }

    private String currentServer() {
        if (mc.getCurrentServerEntry() != null) {
            String address = mc.getCurrentServerEntry().address;
            if (address != null && !address.isBlank()) return address;
        }
        return mc.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    private static String extractSender(String content) {
        int bracket = content.indexOf('>');
        if (bracket > 0) {
            String prefix = content.substring(0, bracket).trim();
            int space = prefix.lastIndexOf(' ');
            return space >= 0 ? prefix.substring(space + 1) : prefix;
        }
        int colon = content.indexOf(':');
        if (colon > 0 && colon < 32) return content.substring(0, colon).trim();
        return "";
    }

    private void feedback(boolean success, String message) {
        if (!chatFeedback.get()) return;
        Formatting color = success ? Formatting.GREEN : Formatting.RED;
        // Passed as an argument so a message containing '%' cannot break the format string.
        warning("%s", color + "[LiveChatRelay] " + message);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WHorizontalList controls = list.add(theme.horizontalList()).expandX().widget();
        WButton sendNow = controls.add(theme.button("Send Now")).expandX().widget();
        sendNow.action = this::sendNow;
        WButton view = controls.add(theme.button("View Saved Reports")).expandX().widget();
        view.action = this::openReportBrowser;

        WButton cleanup = list.add(theme.button("Run Archive Cleanup")).expandX().widget();
        cleanup.action = this::runCleanup;

        return list;
    }
}
