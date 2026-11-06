package com.logvex.addon.framework.utils.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;

/**
 * Maps a player-inventory index to the slot id the screen handler expects.
 *
 * <p>Only the player inventory and the creative inventory are mapped: those are the two handlers the
 * addon interacts with. Any other open container returns {@code -1}, which the caller treats as
 * "cannot act", rather than guessing a slot.
 */
public final class SlotUtils {
    public static final int HOTBAR_START = 0;
    public static final int HOTBAR_END = 8;
    public static final int OFFHAND = 45;
    public static final int MAIN_START = 9;
    public static final int MAIN_END = 35;
    public static final int ARMOR_START = 36;
    public static final int ARMOR_END = 39;

    private SlotUtils() {
    }

    public static int indexToId(int i) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;
        ScreenHandler handler = mc.player.currentScreenHandler;

        if (handler instanceof PlayerScreenHandler) return survivalInventory(i);
        if (handler instanceof CreativeInventoryScreen.CreativeScreenHandler) return survivalInventory(i);
        return -1;
    }

    private static int survivalInventory(int i) {
        if (isHotbar(i)) return 36 + i;
        if (isArmor(i)) return 5 + (i - 36);
        return i;
    }

    public static boolean isHotbar(int i) {
        return i >= HOTBAR_START && i <= HOTBAR_END;
    }

    public static boolean isMain(int i) {
        return i >= MAIN_START && i <= MAIN_END;
    }

    public static boolean isArmor(int i) {
        return i >= ARMOR_START && i <= ARMOR_END;
    }
}
