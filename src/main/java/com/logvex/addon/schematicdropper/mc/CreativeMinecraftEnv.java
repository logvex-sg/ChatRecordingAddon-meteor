package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.AcquisitionEnv;
import com.logvex.addon.schematicdropper.core.SlotInfo;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

import static com.logvex.addon.framework.Addon.mc;

/**
 * Reads the live client for the acquisition workflow: creative state, held quantities and the
 * writable player-inventory slots. Never mutates anything.
 *
 * <p>Only the hotbar and main inventory rows (indices {@code 0..35}) are considered writable.
 * Armour and off-hand slots are deliberately excluded because the creative give targets a
 * {@link net.minecraft.screen.PlayerScreenHandler} slot id and mixing the two would silently
 * write to the wrong place.
 */
public final class CreativeMinecraftEnv implements AcquisitionEnv {
    /** Player-inventory indices the creative give may write to. */
    private static final int FIRST_SLOT = 0;
    private static final int LAST_SLOT = 35;

    @Override
    public boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null;
    }

    @Override
    public boolean isCreative() {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (!mc.interactionManager.hasCreativeInventory()) return false;
        return mc.player.isCreative() || gameMode().isCreative();
    }

    @Override
    public boolean canInteract() {
        return mc.player != null && mc.player.currentScreenHandler != null;
    }

    @Override
    public int countOf(String itemId) {
        Item item = item(itemId);
        if (item == null || mc.player == null) return 0;

        PlayerInventory inventory = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    @Override
    public List<SlotInfo> writableSlots() {
        List<SlotInfo> slots = new ArrayList<>(LAST_SLOT - FIRST_SLOT + 1);
        if (mc.player == null) return slots;

        PlayerInventory inventory = mc.player.getInventory();
        int size = inventory.size();
        for (int i = FIRST_SLOT; i <= LAST_SLOT && i < size; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                slots.add(new SlotInfo(i, null, 0, 0));
            } else {
                slots.add(new SlotInfo(i, Registries.ITEM.getId(stack.getItem()).toString(),
                    stack.getCount(), stack.getMaxCount()));
            }
        }
        return slots;
    }

    @Override
    public int stackLimitOf(String itemId) {
        Item item = item(itemId);
        if (item == null) return 0;
        return new ItemStack(item).getMaxCount();
    }

    @Override
    public boolean isKnownItem(String itemId) {
        return item(itemId) != null;
    }

    private GameMode gameMode() {
        if (mc.interactionManager == null) return GameMode.SURVIVAL;
        return mc.interactionManager.getCurrentGameMode();
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }
}
