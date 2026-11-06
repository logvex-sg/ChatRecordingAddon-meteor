package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;

import java.util.List;

/**
 * Periodic and on-demand summaries of chat activity.
 *
 * <p>The digest reads the same in-memory live buffer the rest of the addon uses, so it never
 * touches the disk from the client thread. It is purely informational: it prints a short summary to
 * chat and, optionally, forwards it through the webhook queue.
 */
public class ChatDigestModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> auto = sgGeneral.add(new BoolSetting.Builder()
        .name("automatic")
        .description("Print a digest automatically on a timer.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> intervalMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("interval-minutes")
        .description("Minutes between automatic digests.")
        .defaultValue(30)
        .min(1)
        .sliderRange(1, 240)
        .visible(auto::get)
        .build()
    );

    private final Setting<Integer> windowSize = sgGeneral.add(new IntSetting.Builder()
        .name("window-size")
        .description("How many recent messages a digest covers.")
        .defaultValue(200)
        .min(20)
        .sliderRange(20, LogService.LIVE_BUFFER_SIZE)
        .build()
    );

    private final Setting<Integer> topPlayers = sgGeneral.add(new IntSetting.Builder()
        .name("top-players")
        .description("How many players to list in a digest.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> sendWebhook = sgGeneral.add(new BoolSetting.Builder()
        .name("send-to-webhook")
        .description("Also send each digest to the configured webhook.")
        .defaultValue(false)
        .build()
    );

    private final LogService service = LogService.get();
    private long lastDigestAt;

    public ChatDigestModule() {
        super(LogAddon.CATEGORY, "chat-digest",
            "Summarises chat activity on demand and on a timer.");
    }

    @Override
    public void onActivate() {
        lastDigestAt = System.currentTimeMillis();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!auto.get()) return;
        long interval = intervalMinutes.get() * 60_000L;
        if (System.currentTimeMillis() - lastDigestAt < interval) return;
        lastDigestAt = System.currentTimeMillis();
        digest(false);
    }

    /** Computes the digest over the configured window. */
    public ChatDigest.Summary summary() {
        List<ChatEntry> entries = service.recent(windowSize.get());
        return ChatDigest.summarise(entries, topPlayers.get());
    }

    /** Prints (and optionally sends) a digest. */
    public void digest(boolean manual) {
        ChatDigest.Summary summary = summary();
        if (summary.isEmpty()) {
            if (manual) info("No messages to summarise yet.");
            return;
        }
        for (String line : ChatDigest.lines(summary, System.currentTimeMillis())) {
            info("%s", line);
        }
        if (sendWebhook.get()) {
            ChatLoggerModule logger = com.logvex.addon.framework.systems.modules.Modules.get()
                .get(ChatLoggerModule.class);
            if (logger != null) {
                service.notifySummary(logger.webhookUrl(), logger.webhookUsername(), service.recent(windowSize.get()));
            }
        }
    }

    /** A one-line headline for the HUD or a notification. */
    public String headline() {
        return ChatDigest.headline(summary());
    }

    @Override
    public String getInfoString() {
        return Messages.plural(summary().totalMessages(), "msg");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton now = list.add(theme.button("Summarise Now")).expandX().widget();
        now.action = () -> digest(true);
        WButton open = list.add(theme.button("Open Digest")).expandX().widget();
        open.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.DigestScreen(this));
        return list;
    }
}
