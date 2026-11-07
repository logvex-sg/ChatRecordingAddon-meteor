package com.logvex.addon.automation.mc;

import com.logvex.addon.automation.core.CreativeEnv;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import static com.logvex.addon.framework.Addon.mc;

/**
 * Reads the live client for the creative workflow: held quantities and the writable player-inventory
 * slots. Never mutates anything, so it is safe to call on every tick.
 *
 * <p>Only the hotbar and main inventory rows (indices {@code 0..35}) are considered, matching the
 * slots a Creative give can target. Armour and off-hand are excluded because their screen-handler
 * ids differ from the player-inventory indices and mixing the two would silently write to the wrong
 * slot.
 */
public final class MinecraftCreativeEnv implements CreativeEnv {
    /** Player-inventory indices the creative give may write to. */
    private static final int LAST_SLOT = 35;

    @Override
    public boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null;
    }

    @Override
    public boolean knownItem(String itemId) {
        return item(itemId) != null;
    }

    @Override
    public int countOf(String itemId) {
        Item item = item(itemId);
        if (item == null || mc.player == null) return 0;
        PlayerInventory inventory = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i <= LAST_SLOT && i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    @Override
    public int freeSlots() {
        if (mc.player == null) return 0;
        PlayerInventory inventory = mc.player.getInventory();
        int free = 0;
        for (int i = 0; i <= LAST_SLOT && i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) free++;
        }
        return free;
    }

    @Override
    public int stackLimit(String itemId) {
        Item item = item(itemId);
        if (item == null) return 64;
        return new ItemStack(item).getMaxCount();
    }

    @Override
    public int firstEmptySlot() {
        if (mc.player == null) return -1;
        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i <= LAST_SLOT && i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    @Override
    public int firstSlotContaining(String itemId) {
        Item item = item(itemId);
        if (item == null || mc.player == null) return -1;
        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i <= LAST_SLOT && i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) return i;
        }
        return -1;
    }

    @Override
    public int countInSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > LAST_SLOT) return 0;
        return mc.player.getInventory().getStack(slot).getCount();
    }

    @Override
    public int inventorySize() {
        return LAST_SLOT + 1;
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }
}
