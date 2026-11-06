package com.logvex.addon.framework.utils.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.function.Predicate;

/** Inventory queries and the hotbar swap used when placing a block. */
public final class InvUtils {
    public static int previousSlot = -1;

    private InvUtils() {
    }

    public static boolean testInMainHand(Predicate<ItemStack> predicate) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && predicate.test(mc.player.getMainHandStack());
    }

    public static boolean testInMainHand(Item... items) {
        return testInMainHand(stack -> matches(stack, items));
    }

    public static boolean testInOffHand(Predicate<ItemStack> predicate) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && predicate.test(mc.player.getOffHandStack());
    }

    public static boolean testInOffHand(Item... items) {
        return testInOffHand(stack -> matches(stack, items));
    }

    public static boolean testInHands(Predicate<ItemStack> predicate) {
        return testInMainHand(predicate) || testInOffHand(predicate);
    }

    public static FindItemResult findEmpty() {
        return find(ItemStack::isEmpty);
    }

    public static FindItemResult findInHotbar(Item... items) {
        return findInHotbar(stack -> matches(stack, items));
    }

    public static FindItemResult findInHotbar(Predicate<ItemStack> isGood) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return new FindItemResult(-1, 0);
        if (testInOffHand(isGood)) return new FindItemResult(SlotUtils.OFFHAND, mc.player.getOffHandStack().getCount());
        if (testInMainHand(isGood)) {
            return new FindItemResult(mc.player.getInventory().selectedSlot, mc.player.getMainHandStack().getCount());
        }
        return find(isGood, SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END);
    }

    public static FindItemResult find(Item... items) {
        return find(stack -> matches(stack, items));
    }

    public static FindItemResult find(Predicate<ItemStack> isGood) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return new FindItemResult(-1, 0);
        return find(isGood, 0, mc.player.getInventory().size() - 1);
    }

    public static FindItemResult find(Predicate<ItemStack> isGood, int start, int end) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return new FindItemResult(-1, 0);

        int slot = -1;
        int count = 0;
        int size = mc.player.getInventory().size();
        for (int i = Math.max(0, start); i <= end && i < size; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isGood.test(stack)) {
                if (slot == -1) slot = i;
                count += stack.getCount();
            }
        }
        return new FindItemResult(slot, count);
    }

    /** Moves {@code slot} into the selected hotbar slot, remembering the previous selection. */
    public static void swap(int slot, boolean swapBack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || slot < 0) return;
        if (!SlotUtils.isHotbar(slot)) return;

        if (swapBack) previousSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;
    }

    public static void swapBack() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || previousSlot == -1) return;
        mc.player.getInventory().selectedSlot = previousSlot;
        previousSlot = -1;
    }

    private static boolean matches(ItemStack stack, Item[] items) {
        for (Item item : items) {
            if (stack.isOf(item)) return true;
        }
        return false;
    }
}
