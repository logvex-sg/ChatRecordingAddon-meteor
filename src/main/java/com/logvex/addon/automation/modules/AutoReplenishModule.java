package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.AutoReplenishController;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the hotbar stocked with the items the player wants to hand.
 *
 * <p>The player lists the items they want on the hotbar; each tick the module asks
 * {@link AutoReplenishController} where to find them and moves a stack into place with a normal
 * slot swap. It only ever moves items that are already in the inventory, so nothing is created and
 * the worst case is that nothing moves. It never takes an item from a hotbar slot that another
 * requested item needs, so two entries cannot fight over one stack.
 */
public class AutoReplenishModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> items = sgGeneral.add(new StringListSetting.Builder()
        .name("items")
        .description("Item ids to keep on the hotbar, in order. The first goes to slot 1, the next to slot 2, and so on.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Boolean> refillFromInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("refill-from-inventory")
        .description("Pull the item from the main inventory when it is not already on the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyWhenIdle = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-idle")
        .description("Do not rearrange the hotbar while a block is being mined or an item is in use.")
        .defaultValue(true)
        .build()
    );

    private final AutoReplenishController controller = new AutoReplenishController();

    /** Slots already moved this session, so a failed move is not retried every tick. */
    private final Map<Integer, String> settled = new LinkedHashMap<>();
    private long lastAttemptAt;

    public AutoReplenishModule() {
        super("auto-replenish", "Keeps the items you choose stocked on the hotbar.");
    }

    @Override
    public void onActivate() {
        controller.setEnabled(true);
        controller.setRefillFromInventory(refillFromInventory.get());
        controller.setMinCount(1);
        settled.clear();
        info("Auto Replenish on (%s configured).", com.logvex.addon.common.Messages.plural(items.get().size(), "item"));
    }

    @Override
    public void onDeactivate() {
        controller.setEnabled(false);
        settled.clear();
        info("Auto Replenish off.");
    }

    @Override
    protected void onWorldLeave() {
        settled.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!ready()) return;
        controller.setRefillFromInventory(refillFromInventory.get());

        if (onlyWhenIdle.get() && isBusy()) return;

        // Re-checking every tick would thrash the inventory; a short interval is plenty for a
        // rearrangement that only matters between tasks.
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < 1000) return;

        Map<Integer, String> desired = desiredSlots();
        if (desired.isEmpty()) return;

        List<AutoReplenishController.Assignment> plan =
            controller.plan(desired, hotbarContents(), inventoryContents());
        if (plan.isEmpty()) return;

        AutoReplenishController.Assignment first = plan.get(0);
        if (moveToHotbar(first.hotbarSlot(), first.sourceSlot())) {
            settled.put(first.hotbarSlot(), first.itemId());
        }
        lastAttemptAt = now;
    }

    private boolean isBusy() {
        if (mc.interactionManager != null && mc.interactionManager.isBreakingBlock()) return true;
        return mc.player != null && mc.player.isUsingItem();
    }

    /** The requested item for each hotbar slot, in list order. */
    private Map<Integer, String> desiredSlots() {
        Map<Integer, String> out = new LinkedHashMap<>();
        List<String> list = items.get();
        for (int i = 0; i < list.size() && i <= 8; i++) {
            String id = list.get(i);
            if (id == null || id.isBlank()) continue;
            out.put(i, id.trim());
        }
        return out;
    }

    private Map<Integer, String> hotbarContents() {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (mc.player == null) return out;
        for (int i = 0; i <= 8; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) out.put(i, Registries.ITEM.getId(stack.getItem()).toString());
        }
        return out;
    }

    private Map<Integer, String> inventoryContents() {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (mc.player == null) return out;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) out.put(i, Registries.ITEM.getId(stack.getItem()).toString());
        }
        return out;
    }

    /**
     * Swaps the item from {@code sourceSlot} into hotbar slot {@code hotbarSlot} with the vanilla
     * swap-click. The click is sent to the player's own screen handler with the destination hotbar
     * index as the button, which is exactly the "swap with hotbar" action the inventory screen uses,
     * so the server validates it like any other inventory click.
     */
    private boolean moveToHotbar(int hotbarSlot, int sourceSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (hotbarSlot < 0 || hotbarSlot > 8 || sourceSlot < 0) return false;

        // Player-inventory index -> screen-handler slot id: hotbar indices 0..8 are slots 36..44,
        // main-inventory indices 9..35 are the same number.
        int slotId = sourceSlot < 9 ? 36 + sourceSlot : sourceSlot;
        int syncId = mc.player.playerScreenHandler.syncId;
        mc.interactionManager.clickSlot(syncId, slotId, hotbarSlot,
            net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
        return true;
    }

    @Override
    public String getInfoString() {
        return controller.enabled() ? com.logvex.addon.common.Messages.plural(items.get().size(), "item") : "off";
    }
}
