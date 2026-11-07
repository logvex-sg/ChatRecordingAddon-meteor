package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.SprintController;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;

/**
 * Keeps the player sprinting while they are moving forward.
 *
 * <p>Vanilla already sprints on a double-tap of forward, but it stops the moment the player is even
 * slightly hungry. This holds sprint on whenever it is worthwhile, and optionally respects a food
 * threshold so a Survival player does not drain their hunger for no reason. The decision is a pure
 * function in {@link SprintController}.
 */
public class AutoSprintModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> requireHunger = sgGeneral.add(new BoolSetting.Builder()
        .name("require-hunger")
        .description("Only sprint when the food level is above the threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minFood = sgGeneral.add(new IntSetting.Builder()
        .name("min-food")
        .description("Minimum food level required to sprint.")
        .defaultValue(6)
        .sliderRange(0, 20)
        .visible(requireHunger::get)
        .build()
    );

    private final SprintController controller = new SprintController(true, 6);

    public AutoSprintModule() {
        super("auto-sprint", "Keeps you sprinting whenever you move forward.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setRequireHunger(requireHunger.get());
        controller.setMinFood(minFood.get());
        info("Auto Sprint on.");
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        if (mc.player != null) mc.player.setSprinting(false);
        info("Auto Sprint off.");
    }

    @Override
    protected void onWorldLeave() {
        if (mc.player != null) mc.player.setSprinting(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;
        controller.setRequireHunger(requireHunger.get());
        controller.setMinFood(minFood.get());

        boolean forward = mc.options != null && mc.options.forwardKey.isPressed();
        boolean sneaking = mc.player.isSneaking();
        boolean inWater = mc.player.isTouchingWater();
        boolean should = controller.shouldSprint(forward, AutomationSupport.foodLevel(), sneaking, inWater);
        if (should != mc.player.isSprinting()) mc.player.setSprinting(should);
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? "on" : "off";
    }
}
