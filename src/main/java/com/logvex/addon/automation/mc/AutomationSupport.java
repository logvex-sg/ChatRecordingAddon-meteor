package com.logvex.addon.automation.mc;

import com.logvex.addon.automation.core.ArmorGuardController;
import com.logvex.addon.framework.utils.player.PlayerUtils;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * Small, shared reads of the live client used by the automation modules.
 *
 * <p>Every method here only observes; nothing mutates the world. Keeping the reads in one place means
 * the modules stay thin and the {@code core} controllers keep receiving plain values, so their
 * decisions remain unit-testable without a running client.
 */
public final class AutomationSupport {
    private AutomationSupport() {
    }

    // ------------------------------------------------------------------ player

    /** The player's food level, or 20 when unknown. */
    public static int foodLevel() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return 20;
        return player.getHungerManager().getFoodLevel();
    }

    /** The player's health, or a large value when unknown. */
    public static float health() {
        ClientPlayerEntity player = mc.player;
        return player == null ? 20f : player.getHealth();
    }

    /** True when the player is in Creative mode, per the client's own view. */
    public static boolean isCreative() {
        ClientPlayerEntity player = mc.player;
        return player != null && player.isCreative();
    }

    /** True when the player is in Survival mode, per the last server-reported game mode. */
    public static boolean isSurvival() {
        GameMode mode = PlayerUtils.getGameMode();
        return mode == GameMode.SURVIVAL;
    }

    /** The player's game mode name, or {@code "unknown"}. */
    public static String gameModeName() {
        GameMode mode = PlayerUtils.getGameMode();
        return mode == null ? "unknown" : mode.getName();
    }

    /** How many empty player-inventory slots there are (indices 0..35). */
    public static int freeSlots() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return 0;
        int free = 0;
        for (int i = 0; i <= 35 && i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isEmpty()) free++;
        }
        return free;
    }

    // ------------------------------------------------------------------ food

    /** The food item ids held in the hotbar, in slot order. */
    public static List<String> heldFoodIds() {
        List<String> out = new ArrayList<>();
        ClientPlayerEntity player = mc.player;
        if (player == null) return out;
        for (int i = 0; i <= 8; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.get(DataComponentTypes.FOOD) == null) continue;
            out.add(Registries.ITEM.getId(stack.getItem()).toString());
        }
        return out;
    }

    /** How many of the held items are food. */
    public static int heldFoodCount() {
        return heldFoodIds().size();
    }

    /** The nutrition an item restores, or 0 when it is not food. */
    public static int foodValue(String itemId) {
        Item item = item(itemId);
        if (item == null) return 0;
        FoodComponent food = new ItemStack(item).get(DataComponentTypes.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    // ------------------------------------------------------------------ armour

    /** Reads the four vanilla armour slots into {@link ArmorGuardController.Piece} values. */
    public static List<ArmorGuardController.Piece> armorPieces() {
        List<ArmorGuardController.Piece> out = new ArrayList<>(4);
        ClientPlayerEntity player = mc.player;
        if (player == null) return out;

        String[] names = {"Helmet", "Chestplate", "Leggings", "Boots"};
        for (int i = 0; i < 4; i++) {
            ItemStack stack = player.getInventory().getArmorStack(i);
            if (stack.isEmpty() || !stack.isDamageable()) {
                out.add(new ArmorGuardController.Piece(names[i], "", 0, 0,
                    ArmorGuardController.PieceState.NOT_APPLICABLE));
                continue;
            }
            int max = stack.getMaxDamage();
            int left = Math.max(0, max - stack.getDamage());
            out.add(new ArmorGuardController.Piece(names[i],
                Registries.ITEM.getId(stack.getItem()).toString(), left, max,
                ArmorGuardController.PieceState.NOT_APPLICABLE));
        }
        return out;
    }

    // ------------------------------------------------------------------ entities

    /** True when a hostile mob is within {@code radius} blocks of the player. */
    public static boolean hostileNearby(double radius) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return false;
        Box box = player.getBoundingBox().expand(radius);
        for (Entity entity : mc.world.getOtherEntities(player, box)) {
            if (entity instanceof HostileEntity) return true;
        }
        return false;
    }

    /** True when another player is within {@code radius} blocks. */
    public static boolean playerNearby(double radius) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return false;
        Box box = player.getBoundingBox().expand(radius);
        for (Entity entity : mc.world.getOtherEntities(player, box)) {
            if (entity instanceof PlayerEntity other && other != player && other.isAlive()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ world

    /** True when the block at {@code pos} is solid enough to stand on. */
    public static boolean solidAt(BlockPos pos) {
        if (mc.world == null) return false;
        return mc.world.getBlockState(pos).isSolidBlock(mc.world, pos);
    }

    /**
     * How many blocks down the first solid ground is below {@code pos}, or {@link Integer#MAX_VALUE}
     * when none is found within {@code limit} blocks.
     */
    public static int dropBelow(BlockPos pos, int limit) {
        if (mc.world == null) return Integer.MAX_VALUE;
        for (int i = 1; i <= limit; i++) {
            if (mc.world.getBlockState(pos.down(i)).isSolidBlock(mc.world, pos.down(i))) return i;
        }
        return Integer.MAX_VALUE;
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }
}
