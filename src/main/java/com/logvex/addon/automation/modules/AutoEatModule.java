package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.AutoEatController;
import com.logvex.addon.automation.mc.AutomationSupport;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

import java.util.List;

/**
 * Eats food from the hotbar when the player is hungry.
 *
 * <p>It only selects a hotbar slot and uses the held item; it never spawns food, so it works in
 * Survival and on any server that allows eating. The decision of when and what to eat lives in
 * {@link AutoEatController}, which is unit-tested.
 */
public class AutoEatModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("Eat when the food level drops to this value or below.")
        .defaultValue(14)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ms")
        .description("Minimum time between eats, so the client is not spammed with use actions.")
        .defaultValue(1000)
        .min(0)
        .max(10000)
        .build()
    );

    private final Setting<Boolean> preferHighNutrition = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-high-nutrition")
        .description("Eat the held food that restores the most hunger, not just the first one found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseWhenScreenOpen = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-screen-open")
        .description("Do not eat while another screen is open.")
        .defaultValue(true)
        .build()
    );

    private final AutoEatController controller = new AutoEatController(14, 0);

    public AutoEatModule() {
        super("auto-eat", "Automatically eats from the hotbar when hungry.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setThreshold(threshold.get());
        info("Auto Eat on. Eating when hunger is at or below %d.", threshold.get());
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        info("Auto Eat off.");
    }

    @Override
    protected void onWorldLeave() {
        controller.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;
        controller.setThreshold(threshold.get());

        if (pauseWhenScreenOpen.get() && mc.currentScreen != null) return;

        int food = AutomationSupport.foodLevel();
        List<String> held = AutomationSupport.heldFoodIds();
        if (!controller.shouldEat(food, AutomationSupport.health(), held)) return;
        if (!controller.cooldownElapsed(System.currentTimeMillis(), cooldown.get())) return;

        String choice = preferHighNutrition.get()
            ? controller.chooseFood(held, AutomationSupport::foodValue)
            : held.get(0);
        if (choice == null) return;

        if (selectHotbarSlot(choice)) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            controller.markEaten(System.currentTimeMillis());
        }
    }

    /** Selects the first hotbar slot holding {@code itemId}. */
    private boolean selectHotbarSlot(String itemId) {
        if (mc.player == null) return false;
        for (int i = 0; i <= 8; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            if (id.equals(itemId)) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? "on" : "off";
    }
}
