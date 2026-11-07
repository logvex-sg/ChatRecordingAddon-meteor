package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.ServerMonitorController;
import com.logvex.addon.automation.core.SessionTracker;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.events.game.GameJoinedEvent;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;

/**
 * Reports a short server status on a timer and keeps a session tally.
 *
 * <p>Every figure is something the client can actually observe: connection state, round-trip
 * latency from the player list entry, game mode, free inventory slots and how long the session has
 * been open. It does not report server-side tick rate or player counts it cannot see. The cadence
 * lives in {@link ServerMonitorController} and the tallies in {@link SessionTracker}, both
 * unit-tested.
 */
public class ServerMonitorModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("How often to print a status line.")
        .defaultValue(300)
        .sliderRange(30, 3600)
        .build()
    );

    private final Setting<Boolean> includeLatency = sgGeneral.add(new BoolSetting.Builder()
        .name("include-latency")
        .description("Include the round-trip latency reported by the player list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("include-inventory")
        .description("Include the number of free inventory slots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> summaryOnLeave = sgGeneral.add(new BoolSetting.Builder()
        .name("summary-on-leave")
        .description("Print a session summary when the client leaves a world.")
        .defaultValue(true)
        .build()
    );

    private final ServerMonitorController controller = new ServerMonitorController(300_000);
    private final SessionTracker session = new SessionTracker();

    public ServerMonitorModule() {
        super("server-monitor", "Prints a periodic server status and keeps a session summary.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setIntervalMs(interval.get() * 1000L);
        controller.arm(System.currentTimeMillis());
        info("Server Monitor on. Reporting every %d seconds.", interval.get());
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        info("Server Monitor off.");
    }

    @Override
    protected void onWorldJoin() {
        session.start(serverName(), System.currentTimeMillis());
        controller.arm(System.currentTimeMillis());
    }

    @Override
    protected void onWorldLeave() {
        if (summaryOnLeave.get() && session.open()) {
            session.end(System.currentTimeMillis());
            info("%s", session.summary(System.currentTimeMillis()));
        }
        controller.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;
        controller.setIntervalMs(interval.get() * 1000L);
        if (!controller.due(System.currentTimeMillis())) return;
        info("%s", statusLine());
    }

    /** The one-line status, built from what the client can observe. */
    public String statusLine() {
        StringBuilder out = new StringBuilder();
        out.append(Messages.duration(session.duration(System.currentTimeMillis())));
        out.append(" online, ").append(AutomationSupport.gameModeName());
        if (includeLatency.get()) out.append(", ").append(latency()).append(" ms");
        if (includeInventory.get()) out.append(", ").append(AutomationSupport.freeSlots())
            .append(" free slot").append(AutomationSupport.freeSlots() == 1 ? "" : "s");
        out.append('.');
        return out.toString();
    }

    private int latency() {
        if (mc.getNetworkHandler() == null || mc.player == null) return -1;
        var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? -1 : entry.getLatency();
    }

    private String serverName() {
        var entry = mc.getCurrentServerEntry();
        if (entry == null) return "single-player";
        return entry.name == null || entry.name.isBlank() ? entry.address : entry.name;
    }

    public SessionTracker session() {
        return session;
    }

    @Override
    public String getInfoString() {
        return controller.enabled()
            ? controller.millisUntilNext(System.currentTimeMillis()) / 1000 + "s" : "off";
    }
}
