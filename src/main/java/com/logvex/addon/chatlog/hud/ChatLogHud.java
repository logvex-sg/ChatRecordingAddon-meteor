package com.logvex.addon.chatlog.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.DuplicateGrouper;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.modules.ChatMonitorModule;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.modules.Modules;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.framework.utils.render.color.SettingColor;

import java.util.List;

/**
 * Live chat feed HUD. Shows the most recent recorded messages with optional timestamps, sender
 * names and highlighting, and a one-line recording status.
 */
public class ChatLogHud extends HudElement {
    public static final HudElementInfo<ChatLogHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "chat-log", "Live chat log feed.", ChatLogHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color HIGHLIGHT = new Color(255, 215, 120);
    private static final Color TEXT = new Color(230, 230, 230);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Integer> lines = sgGeneral.add(new IntSetting.Builder()
        .name("lines")
        .description("Number of recent messages to show.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Show the recording status line.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTimestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("show-timestamp")
        .description("Show the time of each message.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showSender = sgGeneral.add(new BoolSetting.Builder()
        .name("show-sender")
        .description("Show the sender of each message.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showType = sgGeneral.add(new BoolSetting.Builder()
        .name("show-type")
        .description("Show the message category for lines without a sender.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> groupDuplicates = sgGeneral.add(new BoolSetting.Builder()
        .name("group-duplicates")
        .description("Collapse repeated consecutive messages into one line with a repeat count.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a background behind the HUD.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgStyle.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color.")
        .defaultValue(new SettingColor(12, 12, 16, 170))
        .visible(background::get)
        .build()
    );

    private final Setting<Boolean> border = sgStyle.add(new BoolSetting.Builder()
        .name("border")
        .description("Draw a border around the HUD.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> borderColor = sgStyle.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color.")
        .defaultValue(new SettingColor(80, 80, 100))
        .visible(border::get)
        .build()
    );

    private final Setting<SettingColor> titleColor = sgStyle.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Title color.")
        .defaultValue(new SettingColor(140, 200, 255))
        .build()
    );

    private final Setting<Boolean> customScale = sgStyle.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Use a custom text scale instead of the global one.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgStyle.add(new com.logvex.addon.framework.settings.DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .visible(customScale::get)
        .build()
    );

    private final LogService service = LogService.get();

    public ChatLogHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private ChatMonitorModule monitor() {
        return Modules.get().get(ChatMonitorModule.class);
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;

        double width = renderer.textWidth("Log Addon", true, scaleValue);
        for (String line : previewLines()) {
            width = Math.max(width, renderer.textWidth(line, true, scaleValue));
        }
        width = Math.min(width, 320);

        double height = lineHeight; // title
        height += previewLines().size() * lineHeight;
        if (showStatus.get()) height += lineHeight;

        setSize(width + 6, height + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
        if (border.get()) drawBorder(renderer);

        renderer.text("Log Addon", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        ChatMonitorModule monitor = monitor();
        List<ChatEntry> feed = monitor == null
            ? service.recent(lines.get())
            : monitor.visibleFeed(lines.get());

        if (feed.isEmpty()) {
            renderer.text("No messages yet", left, cursor, MUTED, true, scaleValue);
            cursor += lineHeight;
        } else {
            for (DuplicateGrouper.Group group : grouped(feed)) {
                boolean highlighted = monitor != null && monitor.isHighlighted(group.entry());
                renderer.text(compose(group.entry(), highlighted) + DuplicateGrouper.suffix(group.count()),
                    left, cursor, highlighted ? HIGHLIGHT : TEXT, true, scaleValue);
                cursor += lineHeight;
            }
        }

        if (showStatus.get()) {
            String status = (service.isRecording() ? "Recording" : "Paused")
                + "  " + service.liveCount() + " live";
            renderer.text(status, left, cursor, service.isRecording() ? titleColor.get() : MUTED, true, scaleValue);
        }
    }

    private String compose(ChatEntry entry, boolean highlighted) {
        StringBuilder sb = new StringBuilder();
        if (showTimestamp.get()) sb.append(ReportFormatting.shortTime(entry.timestamp())).append(' ');
        if (showSender.get() && !entry.sender().isBlank()) {
            sb.append(entry.sender()).append(": ");
        } else if (showType.get()) {
            sb.append('[').append(entry.type()).append("] ");
        }
        sb.append(entry.content());
        return trim(sb.toString(), 64);
    }

    private List<String> previewLines() {
        ChatMonitorModule monitor = monitor();
        List<ChatEntry> feed = monitor == null
            ? service.recent(lines.get())
            : monitor.visibleFeed(lines.get());
        if (feed.isEmpty()) return List.of("No messages yet");
        List<String> out = new java.util.ArrayList<>();
        for (DuplicateGrouper.Group group : grouped(feed)) {
            out.add(compose(group.entry(), false) + DuplicateGrouper.suffix(group.count()));
        }
        return out;
    }

    /** Consecutive repeats collapsed when grouping is on, otherwise one group per message. */
    private List<DuplicateGrouper.Group> grouped(List<ChatEntry> feed) {
        if (groupDuplicates.get()) return DuplicateGrouper.group(feed);
        List<DuplicateGrouper.Group> out = new java.util.ArrayList<>(feed.size());
        for (ChatEntry entry : feed) out.add(new DuplicateGrouper.Group(entry, 1));
        return out;
    }

    private void drawBorder(HudRenderer renderer) {
        Color c = borderColor.get();
        renderer.quad(x, y, getWidth(), 1, c);
        renderer.quad(x, y + getHeight() - 1, getWidth(), 1, c);
        renderer.quad(x, y, 1, getHeight(), c);
        renderer.quad(x + getWidth() - 1, y, 1, getHeight(), c);
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }
}
