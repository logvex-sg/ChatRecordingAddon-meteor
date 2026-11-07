package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.PlayerIdentity;
import com.logvex.addon.chatlog.core.PlayerIdentityTracker;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.mc.PlayerIdentityService;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.notify.core.NotificationPriority;
import com.logvex.addon.notify.core.NotificationType;
import com.logvex.addon.notify.mc.NotificationService;

import java.util.List;

/**
 * Tracks observed account names and reports renames.
 *
 * <p>A client cannot see a rename history; it can only notice that the same UUID is later announced
 * under a different name. This module surfaces exactly that and nothing more. Names come from the
 * server player list, so a player who never appears there is never claimed.
 */
public class PlayerIdentityModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNotify = settings.createGroup("Notifications");

    private final Setting<Boolean> track = sgGeneral.add(new BoolSetting.Builder()
        .name("track-identities")
        .description("Record the names each account is seen using.")
        .defaultValue(true)
        .onChanged(value -> PlayerIdentityService.get().setEnabled(value))
        .build()
    );

    private final Setting<Boolean> notifyRenames = sgNotify.add(new BoolSetting.Builder()
        .name("notify-renames")
        .description("Raise a notification when an account is seen under a new name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyWatchedOnly = sgNotify.add(new BoolSetting.Builder()
        .name("watched-only")
        .description("Only notify for players on the watchlist.")
        .defaultValue(false)
        .visible(notifyRenames::get)
        .build()
    );

    private final Setting<List<String>> watchlist = sgGeneral.add(new StringListSetting.Builder()
        .name("watchlist")
        .description("Player names to watch (one per line). Watched renames are always highlighted.")
        .defaultValue()
        .onChanged(value -> LogService.get().watchlist().set(value))
        .build()
    );

    private final LogService service = LogService.get();
    private final PlayerIdentityService identities = PlayerIdentityService.get();

    private final PlayerIdentityService.RenameListener listener = this::onRename;

    public PlayerIdentityModule() {
        super(LogClient.CATEGORY, "player-identity",
            "Tracks observed player name changes and a per-account watchlist.");
    }

    @Override
    public void onActivate() {
        identities.setEnabled(track.get());
        service.watchlist().set(watchlist.get());
        identities.addListener(listener);
    }

    @Override
    public void onDeactivate() {
        identities.removeListener(listener);
    }

    private void onRename(PlayerIdentityTracker.Rename rename) {
        boolean watched = service.watchlist().contains(rename.to()) || service.watchlist().contains(rename.from());
        if (!notifyRenames.get()) return;
        if (notifyWatchedOnly.get() && !watched) return;

        String message = rename.from() + " is now known as " + rename.to() + ".";
        service.alerts().record(com.logvex.addon.chatlog.core.AlertLog.Kind.KEYWORD,
            "Rename", message);
        NotificationService.get().notify(
            NotificationType.PLAYER_ACTIVITY,
            watched ? NotificationPriority.HIGH : NotificationPriority.NORMAL,
            "player-identity", "Player renamed", message);
        if (chatFeedback) info("%s", message);
    }

    /** Accounts matching {@code filter}, most recently seen first. */
    public List<PlayerIdentity> identities(String filter) {
        return service.identities().search(filter);
    }

    /** Only the accounts observed under more than one name. */
    public List<PlayerIdentity> renamed() {
        return service.identities().renamed();
    }

    /** Every observed rename, newest first. */
    public List<PlayerIdentityTracker.Rename> renames() {
        return service.identities().renames();
    }

    public int trackedAccounts() {
        return service.identities().size();
    }

    public int renameCount() {
        return service.identities().renameCount();
    }

    @Override
    public String getInfoString() {
        int renames = renameCount();
        return renames == 0 ? trackedAccounts() + " ids" : renames + " renames";
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Player Identity")).expandX().widget();
        open.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.PlayerIdentityScreen(this));

        WButton forgetAll = list.add(theme.button("Clear Identity Index")).expandX().widget();
        forgetAll.action = () -> {
            service.identities().clear();
            identities.saveAsync();
            info("Identity index cleared.");
        };
        return list;
    }
}
