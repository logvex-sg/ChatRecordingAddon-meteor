package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.AutoLogController;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.DoubleSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.notify.core.NotificationType;
import net.minecraft.text.Text;

/**
 * Disconnects from the server when the player is in danger.
 *
 * <p>Each rule is a plain threshold, evaluated by {@link AutoLogController}; the module only performs
 * the disconnect when a rule fires. A grace period after joining prevents an immediate disconnect
 * while health and inventory are still syncing. The module never fights the player: it is a manual
 * safety net, so it is off by default and every rule must be switched on explicitly.
 */
public class AutoLogModule extends AutomationModule {
    private final SettingGroup sgHealth = settings.getDefaultGroup();
    private final SettingGroup sgDanger = settings.createGroup("Danger");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Boolean> lowHealth = sgHealth.add(new BoolSetting.Builder()
        .name("on-low-health")
        .description("Disconnect when health drops to the threshold or below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> healthThreshold = sgHealth.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("Health (half-hearts) at or below which the player leaves.")
        .defaultValue(6.0)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .visible(lowHealth::get)
        .build()
    );

    private final Setting<Boolean> noFood = sgHealth.add(new BoolSetting.Builder()
        .name("on-no-food")
        .description("Disconnect when the food level drops to the threshold or below.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> foodThreshold = sgHealth.add(new IntSetting.Builder()
        .name("food-threshold")
        .description("Food level at or below which the player leaves.")
        .defaultValue(2)
        .sliderRange(0, 20)
        .visible(noFood::get)
        .build()
    );

    private final Setting<Boolean> fullInventory = sgDanger.add(new BoolSetting.Builder()
        .name("on-full-inventory")
        .description("Disconnect when the inventory has no free slot.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hostile = sgDanger.add(new BoolSetting.Builder()
        .name("on-hostile-mob")
        .description("Disconnect when a hostile mob is within the radius.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> hostileRadius = sgDanger.add(new DoubleSetting.Builder()
        .name("hostile-radius")
        .description("How close a hostile mob must be.")
        .defaultValue(8.0)
        .min(1)
        .max(64)
        .sliderRange(1, 32)
        .visible(hostile::get)
        .build()
    );

    private final Setting<Boolean> playerNear = sgDanger.add(new BoolSetting.Builder()
        .name("on-player-nearby")
        .description("Disconnect when another player is within the radius.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> playerRadius = sgDanger.add(new DoubleSetting.Builder()
        .name("player-radius")
        .description("How close another player must be.")
        .defaultValue(16.0)
        .min(1)
        .max(128)
        .sliderRange(1, 64)
        .visible(playerNear::get)
        .build()
    );

    private final Setting<Integer> graceSeconds = sgTiming.add(new IntSetting.Builder()
        .name("grace-seconds")
        .description("Do not disconnect within this many seconds of joining.")
        .defaultValue(10)
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<Integer> rearmSeconds = sgTiming.add(new IntSetting.Builder()
        .name("rearm-seconds")
        .description("After a rule fires but the player stays, wait this long before checking again.")
        .defaultValue(5)
        .sliderRange(1, 60)
        .build()
    );

    private final AutoLogController controller = new AutoLogController(6f, 2, 10_000);

    private long lastTriggeredAt;

    public AutoLogModule() {
        super("auto-log", "Disconnects you when your health, food or surroundings become dangerous.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        pushSettings();
        controller.reset();
        lastTriggeredAt = 0;
        // Enabling after a join would otherwise never arm the grace period.
        if (ready()) controller.onJoin(System.currentTimeMillis());
        info("Auto Log on.");
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        info("Auto Log off.");
    }

    @Override
    protected void onWorldJoin() {
        controller.onJoin(System.currentTimeMillis());
    }

    @Override
    protected void onWorldLeave() {
        controller.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;
        pushSettings();

        long now = System.currentTimeMillis();
        if (!controller.enabled()) return;
        if (now - lastTriggeredAt < rearmSeconds.get() * 1000L) return;

        AutoLogController.Reason reason = controller.evaluate(now,
            lowHealth.get() ? AutomationSupport.health() : Float.MAX_VALUE,
            noFood.get() ? AutomationSupport.foodLevel() : Integer.MAX_VALUE,
            AutomationSupport.freeSlots(),
            hostile.get() && AutomationSupport.hostileNearby(hostileRadius.get()),
            playerNear.get() && AutomationSupport.playerNearby(playerRadius.get()));

        if (reason == AutoLogController.Reason.NONE) return;

        lastTriggeredAt = now;
        String why = AutoLogController.describe(reason);
        warning("Disconnecting: %s.", why);
        notifyUser(NotificationType.CONNECTION, "Auto Log", "Disconnected because " + why + ".");
        disconnect(why);
    }

    private void pushSettings() {
        controller.setHealthThreshold(lowHealth.get() ? (float) (double) healthThreshold.get() : 0f);
        controller.setFoodThreshold(noFood.get() ? foodThreshold.get() : 0);
        controller.setLogOnFullInventory(fullInventory.get());
        controller.setLogOnHostile(hostile.get(), hostileRadius.get());
        controller.setLogOnPlayer(playerNear.get(), playerRadius.get());
        controller.setGraceMs(graceSeconds.get() * 1000L);
    }

    private void disconnect(String why) {
        if (mc.getNetworkHandler() == null) return;
        var connection = mc.getNetworkHandler().getConnection();
        if (connection == null) return;
        connection.disconnect(Text.literal("Auto Log: " + why));
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? "on" : "off";
    }
}
