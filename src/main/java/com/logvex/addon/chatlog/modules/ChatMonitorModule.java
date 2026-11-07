package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.orbit.EventHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live chat monitoring: which messages are shown, which are highlighted, and which are hidden.
 *
 * <p>The module itself is a settings holder and a small filter engine. The {@link com.logvex.addon.chatlog.hud.ChatLogHud}
 * and the dashboard render the feed using {@link #visibleFeed(int)}.
 */
public class ChatMonitorModule extends Module {
    private final SettingGroup sgDisplay = settings.getDefaultGroup();
    private final SettingGroup sgHighlight = settings.createGroup("Highlight");
    private final SettingGroup sgFilters = settings.createGroup("Filters");
    private final SettingGroup sgNotify = settings.createGroup("Notifications");

    private final Setting<Integer> feedSize = sgDisplay.add(new IntSetting.Builder()
        .name("feed-size")
        .description("How many recent messages the live feed keeps.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> showTimestamps = sgDisplay.add(new BoolSetting.Builder()
        .name("show-timestamps")
        .description("Prefix each live message with its time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showSender = sgDisplay.add(new BoolSetting.Builder()
        .name("show-sender")
        .description("Prefix each live message with the sender name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> shownTypes = sgDisplay.add(new StringListSetting.Builder()
        .name("shown-types")
        .description("Message categories shown in the live feed (ids such as public_chat, system).")
        .defaultValue(Arrays.stream(MessageType.values()).map(MessageType::id).toList())
        .build()
    );

    private final Setting<List<String>> highlightWords = sgHighlight.add(new StringListSetting.Builder()
        .name("highlight-words")
        .description("Words highlighted in the live feed.")
        .defaultValue("diamond", "netherite", "elytra")
        .build()
    );

    private final Setting<List<String>> highlightPlayers = sgHighlight.add(new StringListSetting.Builder()
        .name("highlight-players")
        .description("Players whose messages are highlighted.")
        .defaultValue()
        .build()
    );

    private final Setting<List<String>> ignoreWords = sgFilters.add(new StringListSetting.Builder()
        .name("ignore-words")
        .description("Messages containing any of these words are hidden from the live feed.")
        .defaultValue()
        .build()
    );

    private final Setting<List<String>> ignorePlayers = sgFilters.add(new StringListSetting.Builder()
        .name("ignore-players")
        .description("Messages from these players are hidden from the live feed.")
        .defaultValue()
        .build()
    );

    private final Setting<Boolean> notifySounds = sgNotify.add(new BoolSetting.Builder()
        .name("notification-sounds")
        .description("Play a click when a highlighted message arrives.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> desktopNotifications = sgNotify.add(new BoolSetting.Builder()
        .name("desktop-notifications")
        .description("Log highlighted messages to the game log (a desktop-notification hook).")
        .defaultValue(false)
        .build()
    );

    private final LogService service = LogService.get();

    public ChatMonitorModule() {
        super(LogClient.CATEGORY, "chat-monitor", "Live chat feed with search, filters and highlighting.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Nothing to poll: the feed is read on demand. The handler keeps the module ticking so its
        // notification settings are evaluated against newly recorded entries.
        if (!notifySounds.get() && !desktopNotifications.get()) return;
        List<ChatEntry> recent = service.recent(5);
        for (ChatEntry entry : recent) {
            if (!isHighlighted(entry)) continue;
            if (desktopNotifications.get()) {
                LogClient.LOG.info("[Log Client] highlighted: {}", entry.content());
            }
        }
    }

    /** Recent entries passing the current filters, newest first. */
    public List<ChatEntry> visibleFeed(int limit) {
        Set<MessageType> types = new java.util.HashSet<>();
        for (String id : shownTypes.get()) types.add(MessageType.fromString(id));
        List<String> ignores = lower(ignoreWords.get());
        List<String> ignorePlayerList = lower(ignorePlayers.get());

        List<ChatEntry> out = new ArrayList<>();
        for (ChatEntry entry : service.recent(Math.max(limit * 3, 40))) {
            if (!types.isEmpty() && !types.contains(entry.type())) continue;
            if (matchesAny(entry, ignores, ignorePlayerList)) continue;
            out.add(entry);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public boolean isHighlighted(ChatEntry entry) {
        if (entry == null) return false;
        for (String word : highlightWords.get()) {
            if (word != null && !word.isBlank()
                && entry.content().toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        for (String player : highlightPlayers.get()) {
            if (player != null && !player.isBlank() && entry.sender().equalsIgnoreCase(player)) return true;
        }
        return false;
    }

    public boolean showTimestamps() {
        return showTimestamps.get();
    }

    public boolean showSender() {
        return showSender.get();
    }

    public int feedSize() {
        return feedSize.get();
    }

    private static boolean matchesAny(ChatEntry entry, List<String> words, List<String> players) {
        String content = entry.content().toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (!word.isEmpty() && content.contains(word)) return true;
        }
        for (String player : players) {
            if (!player.isEmpty() && entry.sender().toLowerCase(Locale.ROOT).equals(player)) return true;
        }
        return false;
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) out.add(value == null ? "" : value.toLowerCase(Locale.ROOT));
        return out;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Live Logs")).expandX().widget();
        open.action = () -> {
            ChatLoggerModule logger = com.logvex.addon.framework.systems.modules.Modules.get()
                .get(ChatLoggerModule.class);
            if (logger != null) mc.setScreen(new com.logvex.addon.chatlog.gui.LogDashboardScreen(logger, 1));
        };
        return list;
    }
}
