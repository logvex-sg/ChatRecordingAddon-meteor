package com.logvex.addon.update.mc;

import com.logvex.addon.framework.events.game.GameJoinedEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.update.core.UpdateChannel;
import com.logvex.addon.update.core.UpdateSnapshot;
import com.logvex.addon.update.core.UpdateState;
import com.logvex.addon.update.gui.UpdateScreen;

/**
 * Checks GitHub Releases for a newer build and downloads it on request.
 *
 * <p>All network work happens on the update service's daemon worker, so the game thread is never
 * blocked. The module never installs anything: a verified jar is written to
 * {@code <gamedir>/logclient/updates} and the user is told where it is.
 */
public class UpdateModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<UpdateChannel> channel = sgGeneral.add(new EnumSetting.Builder<UpdateChannel>()
        .name("channel")
        .description("Stable only offers full releases; Beta also offers pre-releases.")
        .defaultValue(UpdateChannel.STABLE)
        .onChanged(UpdateService.get()::setChannel)
        .build()
    );

    private final Setting<Boolean> checkOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("check-on-join")
        .description("Check for updates once after joining a world.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDownload = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-download")
        .description("Download a newer release automatically when one is found.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> intervalMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("check-interval-minutes")
        .description("How often to check while the game is running. 0 disables the periodic check.")
        .defaultValue(60)
        .min(0)
        .max(1440)
        .build()
    );

    private final Setting<Boolean> notifyInChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a short line when a check finishes or a download completes.")
        .defaultValue(true)
        .build()
    );

    private long lastCheckMs;
    private boolean checkedThisSession;

    public UpdateModule() {
        super(com.logvex.addon.LogClient.CATEGORY, "updates",
            "Checks GitHub Releases for a newer build and downloads it on request.");
        UpdateService.get().setChannel(channel.get());
    }

    @Override
    public void onActivate() {
        checkedThisSession = false;
        lastCheckMs = 0;
        UpdateService.get().setChannel(channel.get());
        if (notifyInChat.get()) info("Update checks on. Channel: %s.", channel.get().label());
        if (checkOnJoin.get() && mc.world != null) triggerCheck();
    }

    @Override
    public void onDeactivate() {
        if (notifyInChat.get()) info("Update checks off.");
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        if (checkOnJoin.get()) triggerCheck();
    }

    @EventHandler
    private void onTick(com.logvex.addon.framework.events.world.TickEvent.Post event) {
        int interval = intervalMinutes.get();
        if (interval <= 0) return;
        long now = System.currentTimeMillis();
        if (lastCheckMs == 0) {
            lastCheckMs = now;
            return;
        }
        if (now - lastCheckMs >= interval * 60_000L) {
            lastCheckMs = now;
            triggerCheck();
        }
    }

    /** Runs a check now, unless one already ran this session and the user did not ask again. */
    public void triggerCheck() {
        if (checkedThisSession) return;
        checkedThisSession = true;
        UpdateService.get().check();
    }

    /** Forces a check regardless of the once-per-session guard; used by the GUI and commands. */
    public void checkNow() {
        UpdateService.get().check();
    }

    public void downloadNow() {
        UpdateService.get().download();
    }

    public UpdateSnapshot snapshot() {
        return UpdateService.get().snapshot();
    }

    @Override
    public String getInfoString() {
        UpdateSnapshot snapshot = snapshot();
        return switch (snapshot.state()) {
            case AVAILABLE -> "update " + snapshot.latestVersion();
            case DOWNLOADING -> "downloading";
            case READY -> "ready";
            case UP_TO_DATE -> "latest";
            case FAILED -> "failed";
            default -> null;
        };
    }

    @Override
    public com.logvex.addon.framework.gui.widgets.WWidget getWidget(com.logvex.addon.framework.gui.GuiTheme theme) {
        com.logvex.addon.framework.gui.widgets.containers.WVerticalList list =
            new com.logvex.addon.framework.gui.widgets.containers.WVerticalList();
        list.add(theme.label(snapshot().statusLine())).expandX().widget();
        list.add(theme.button("Open Updates screen", () ->
            mc.setScreen(new UpdateScreen(this)))).expandX();
        return list;
    }
}
