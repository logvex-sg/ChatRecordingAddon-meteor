package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.InventoryPort;
import com.logvex.addon.bot.core.InventorySnapshot;
import com.logvex.addon.bot.core.ItemStackView;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.logvex.addon.framework.Addon.mc;

/**
 * {@link InventoryPort} backed by the live client inventory.
 *
 * <p>Snapshots are taken on the client thread and are cheap (one pass over the inventory). Every
 * mutation goes through the interaction manager's slot-click path, which the server validates, so a
 * client-only change can never be mistaken for a real one.
 */
public final class MinecraftInventoryPort implements InventoryPort {
    @Override
    public InventorySnapshot snapshot() {
        if (mc.player == null) return InventorySnapshot.empty();
        PlayerInventory inv = mc.player.getInventory();
        Map<Integer, ItemStackView> slots = new LinkedHashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                slots.put(i, ItemStackView.blank());
                continue;
            }
            Identifier id = Registries.ITEM.getId(stack.getItem());
            slots.put(i, new ItemStackView(id.toString(), stack.getCount(), stack.getMaxCount(),
                stack.getDamage(), stack.isDamageable() ? stack.getMaxDamage() : 0));
        }
        return new InventorySnapshot(slots, inv.selectedSlot);
    }

    @Override
    public int stackSize(String itemId) {
        Item item = item(itemId);
        if (item == null) return 64;
        return new ItemStack(item).getMaxCount();
    }

    @Override
    public boolean knownItem(String itemId) {
        return item(itemId) != null;
    }

    @Override
    public boolean selectHotbar(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        mc.player.getInventory().selectedSlot = slot;
        return true;
    }

    @Override
    public boolean moveToHotbar(String itemId, int amount) {
        if (mc.player == null) return false;
        PlayerInventory inv = mc.player.getInventory();
        Item item = item(itemId);
        if (item == null) return false;
        int slot = inv.getSlotWithStack(new ItemStack(item));
        if (slot < 0) return false;
        if (slot < 9) return selectHotbar(slot);
        return swapWithSlot(slot);
    }

    @Override
    public boolean dropHeld() {
        if (mc.player == null) return false;
        mc.player.dropSelectedItem(false);
        return true;
    }

    @Override
    public boolean swapWithSlot(int inventorySlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        // A swap between the selected hotbar slot and the target slot is a normal slot click.
        int selected = mc.player.getInventory().selectedSlot;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, inventorySlot, selected,
            net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
        return true;
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }
}
