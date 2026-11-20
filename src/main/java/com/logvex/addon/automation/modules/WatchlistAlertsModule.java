package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.PresenceWatcher;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.notify.core.NotificationType;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;

/**
 * Alerts when a player on the watchlist joins or leaves.
 *
 * <p>The chat log already keeps a watchlist, but it only stored names: nothing ever acted on them.
 * This module turns a join or leave line into a notification, so the list finally does what its name
 * says.
 *
 * <p>Only transitions alert. Servers that announce a player twice, or a reconnect that produces a
 * leave immediately followed by a join, would otherwise raise the same notice twice; the decision
 * lives in {@link PresenceWatcher} so it can be tested.
 */
public class WatchlistAlertsModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("on-join")
        .description("Alert when a watched player joins.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onLeave = sgGeneral.add(new BoolSetting.Builder()
        .name("on-leave")
        .description("Alert when a watched player leaves.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a short sound with the alert.")
        .defaultValue(true)
        .build()
    );

    /** Two identical alerts inside this window are treated as one announcement. */
    private static final long DEDUPE_MS = 3_000;

    private final PresenceWatcher watcher = new PresenceWatcher(DEDUPE_MS);
    private LogService.LiveListener listener;

    public WatchlistAlertsModule() {
        super("watchlist-alerts",
            "Alerts when a player on the chat log's watchlist joins or leaves.", "watching");
    }

    @Override
    public void onActivate() {
        listener = this::onEntry;
        LogService.get().addListener(listener);
        info("Watchlist alerts on. Add names with %slog identities watch <player>.",
            com.logvex.addon.framework.systems.config.ClientConfig.get().prefix());
    }

    @Override
    public void onDeactivate() {
        if (listener != null) {
            LogService.get().removeListener(listener);
            listener = null;
        }
        watcher.reset();
        info("Watchlist alerts off.");
    }

    @Override
    protected void onWorldJoin() {
        // A new world has no meaningful presence state; carrying it over would suppress the first
        // alert for anyone who was already online when the client reconnected.
        watcher.reset();
    }

    private void onEntry(ChatEntry entry) {
        SafeEventHandler.run("WatchlistAlertsModule.onEntry", () -> {
            if (entry == null || entry.type() == null) return;
            boolean joined = entry.type() == MessageType.JOIN;
            boolean left = entry.type() == MessageType.LEAVE;
            if (!joined && !left) return;
            if (joined && !onJoin.get()) return;
            if (left && !onLeave.get()) return;

            String player = entry.sender();
            if (player == null || player.isBlank()) return;
            if (!LogService.get().watchlist().contains(player)) return;

            long now = System.currentTimeMillis();
            PresenceWatcher.Alert alert = joined
                ? watcher.joined(player, now)
                : watcher.left(player, now);
            if (alert == null) return;

            String verb = alert.change() == PresenceWatcher.Change.JOINED ? "joined" : "left";
            notifyUser(NotificationType.CONNECTION, "Watchlist", alert.player() + " " + verb + ".");
            if (sound.get() && mc.player != null) {
                mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.6f, 1.4f);
            }
        });
    }

    /** The watched players currently believed online, for the module's own panel. */
    public PresenceWatcher watcher() {
        return watcher;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        var names = LogService.get().watchlist();

        list.add(theme.horizontalSeparator("Watchlist"));
        if (names.isEmpty()) {
            list.add(theme.label("No names watched. Use " + com.logvex.addon.framework.systems.config
                .ClientConfig.get().prefix() + "log identities watch <player>."));
            return list;
        }

        list.add(theme.label(names.size() + " watched, " + watcher.onlinePlayers().size() + " online:"));
        for (String name : names.entries()) {
            list.add(theme.label("  " + name + (watcher.isOnline(name) ? "  (online)" : "")));
        }
        return list;
    }

    @Override
    public String getInfoString() {
        return watcher.onlinePlayers().size() + " online";
    }
}
