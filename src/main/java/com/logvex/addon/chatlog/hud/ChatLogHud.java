package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DuplicateGrouper;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.modules.ChatMonitorModule;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;
import com.logvex.addon.framework.systems.modules.Modules;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Live chat feed HUD. Shows the most recent recorded messages with optional timestamps, sender names
 * and highlighting, and a one-line recording status.
 *
 * <p>The feed is composed once per frame and reused by both the measure and the draw pass, so a
 * twenty-line feed does not do the grouping and truncation twice. A line longer than {@link #MAX_CHARS}
 * is ellipsised, so one long message cannot stretch the panel across the screen.
 */
public class ChatLogHud extends HudElement {
    public static final HudElementInfo<ChatLogHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "chat-log", "Live chat log feed.", ChatLogHud::new);

    private static final int MAX_CHARS = 64;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> lines = sgGeneral.add(new IntSetting.Builder()
        .name("lines").description("Number of recent messages to show.").defaultValue(6).min(1).sliderRange(1, 20).build());
    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status").description("Show the recording status line.").defaultValue(true).build());
    private final Setting<Boolean> showTimestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("show-timestamp").description("Show the time of each message.").defaultValue(false).build());
    private final Setting<Boolean> showSender = sgGeneral.add(new BoolSetting.Builder()
        .name("show-sender").description("Show the sender of each message.").defaultValue(true).build());
    private final Setting<Boolean> showType = sgGeneral.add(new BoolSetting.Builder()
        .name("show-type").description("Show the category for lines without a sender.").defaultValue(false).build());
    private final Setting<Boolean> groupDuplicates = sgGeneral.add(new BoolSetting.Builder()
        .name("group-duplicates").description("Collapse repeated consecutive messages into one line with a repeat count.")
        .defaultValue(false).build());

    private final LogService service = LogService.get();

    /** A composed feed line: the text to draw, whether it is highlighted, and whether it is the status. */
    private record Line(String text, boolean highlighted, boolean status) {
    }

    public ChatLogHud() {
        super(INFO);
    }

    private ChatMonitorModule monitor() {
        return Modules.get().get(ChatMonitorModule.class);
    }

    private List<ChatEntry> feed() {
        ChatMonitorModule monitor = monitor();
        return monitor == null ? service.recent(lines.get()) : monitor.visibleFeed(lines.get());
    }

    /** Consecutive repeats collapsed when grouping is on, otherwise one group per message. */
    private List<DuplicateGrouper.Group> grouped(List<ChatEntry> feed) {
        if (groupDuplicates.get()) return DuplicateGrouper.group(feed);
        List<DuplicateGrouper.Group> out = new ArrayList<>(feed.size());
        for (ChatEntry entry : feed) out.add(new DuplicateGrouper.Group(entry, 1));
        return out;
    }

    private List<Line> compose() {
        ChatMonitorModule monitor = monitor();
        List<ChatEntry> feed = feed();
        List<Line> out = new ArrayList<>();
        if (feed.isEmpty()) {
            out.add(new Line("No messages yet", false, false));
        } else {
            for (DuplicateGrouper.Group group : grouped(feed)) {
                boolean highlighted = monitor != null && monitor.isHighlighted(group.entry());
                out.add(new Line(entry(group.entry()) + DuplicateGrouper.suffix(group.count()), highlighted, false));
            }
        }
        if (showStatus.get()) {
            String status = (service.isRecording() ? "Recording" : "Paused") + "  \u00b7  " + service.liveCount() + " live";
            out.add(new Line(status, false, true));
        }
        return out;
    }

    private String entry(ChatEntry chatEntry) {
        StringBuilder sb = new StringBuilder();
        if (showTimestamp.get()) sb.append(ReportFormatting.shortTime(chatEntry.timestamp())).append(' ');
        if (showSender.get() && !chatEntry.sender().isBlank()) {
            sb.append(chatEntry.sender()).append(": ");
        } else if (showType.get()) {
            sb.append('[').append(chatEntry.type()).append("] ");
        }
        sb.append(chatEntry.content());
        return trim(sb.toString(), MAX_CHARS);
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        List<Line> composed = compose();
        double width = renderer.textWidth("Log Addon", shadow, scale);
        for (Line line : composed) width = Math.max(width, renderer.textWidth(line.text(), shadow, scale));
        double lineHeight = renderer.textHeight(shadow, scale) + HudPanel.LINE_GAP;
        setSize(Math.min(width, 340) + HudPanel.PAD * 2, lineHeight * (composed.size() + 1) + HudPanel.PAD * 2);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Log Addon", scale, shadow);

        for (Line line : compose()) {
            Color color = line.status()
                ? (service.isRecording() ? style.accent() : style.muted())
                : (line.highlighted() ? style.warn() : style.text());
            HudPanel.line(renderer, this, line.text(), cursor, color, scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }
}
