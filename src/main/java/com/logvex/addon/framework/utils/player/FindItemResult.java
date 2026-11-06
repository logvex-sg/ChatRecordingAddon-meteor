package com.logvex.addon.framework.utils.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;

/** The result of an inventory search: the first matching slot and the total matching count. */
public record FindItemResult(int slot, int count) {
    public boolean found() {
        return slot != -1;
    }

    public Hand getHand() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (slot == SlotUtils.OFFHAND) return Hand.OFF_HAND;
        if (mc.player != null && slot == mc.player.getInventory().selectedSlot) return Hand.MAIN_HAND;
        return null;
    }

    public boolean isMainHand() {
        return getHand() == Hand.MAIN_HAND;
    }

    public boolean isOffhand() {
        return getHand() == Hand.OFF_HAND;
    }

    public boolean isHotbar() {
        return SlotUtils.isHotbar(slot);
    }

    public boolean isMain() {
        return SlotUtils.isMain(slot);
    }

    public boolean isArmor() {
        return SlotUtils.isArmor(slot);
    }
}
