package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.DeathTracker;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.notify.core.NotificationType;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.orbit.EventHandler;

/**
 * Remembers where you died, so the spot can be found again.
 *
 * <p>The vanilla death screen is gone as soon as it is dismissed, and on a busy server the death
 * message scrolls away with it. This keeps the coordinates.
 *
 * <p>Position is sampled while alive and captured on the tick health first reads zero. That is the
 * last moment the client still knows where the body is; after the respawn the player has moved, and
 * the death screen has no coordinates in it.
 */
public class DeathTrackerModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print the coordinates when you die.")
        .defaultValue(true)
        .build()
    );

    private final DeathTracker tracker = new DeathTracker();
    /** The last position seen while alive, so the death tick has somewhere to point. */
    private double lastX;
    private double lastY;
    private double lastZ;
    private String lastWorld = "";
    private boolean wasAlive;

    public DeathTrackerModule() {
        super("death-tracker",
            "Remembers where you died. List with !deaths, return with !back.", "deaths");
    }

    @Override
    public void onActivate() {
        wasAlive = false;
        info("Death Tracker on.");
    }

    @Override
    public void onDeactivate() {
        info("Death Tracker off.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;

        boolean alive = mc.player.getHealth() > 0;

        if (alive) {
            // Sample while alive: on the tick health reaches zero the player is already being
            // moved, so this is the last trustworthy position.
            lastX = mc.player.getX();
            lastY = mc.player.getY();
            lastZ = mc.player.getZ();
            lastWorld = dimension();
            wasAlive = true;
            return;
        }

        // Health is zero. Only record the transition, so the several ticks spent dead before the
        // respawn do not create one entry each.
        if (!wasAlive) return;
        wasAlive = false;

        tracker.record(lastWorld, (int) Math.floor(lastX), (int) Math.floor(lastY),
            (int) Math.floor(lastZ), System.currentTimeMillis(), "");

        DeathTracker.Death death = tracker.last();
        if (death == null) return;
        if (announce.get()) {
            info("You died at %s (%s). Use %sback to see it.",
                death.coords(), shortWorld(death.world()),
                com.logvex.addon.framework.systems.config.ClientConfig.get().prefix());
        }
        notifyUser(NotificationType.CONNECTION, "Death", "Died at " + death.coords());
    }

    /** The current dimension id, such as {@code minecraft:overworld}. */
    private String dimension() {
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    /** Just the path, so a message reads "overworld" rather than "minecraft:overworld". */
    public static String shortWorld(String world) {
        if (world == null || world.isBlank()) return "unknown";
        int colon = world.indexOf(':');
        return colon >= 0 ? world.substring(colon + 1) : world;
    }

    /** The remembered deaths, for the commands that list and return to them. */
    public DeathTracker tracker() {
        return tracker;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.horizontalSeparator("Recent deaths"));
        if (tracker.size() == 0) {
            list.add(theme.label("Nothing recorded yet."));
            return list;
        }
        for (DeathTracker.Death death : tracker.recent()) {
            list.add(theme.label(shortWorld(death.world()) + "  " + death.coords()));
        }
        return list;
    }

    @Override
    public String getInfoString() {
        DeathTracker.Death last = tracker.last();
        return last == null ? "none" : last.coords();
    }
}
