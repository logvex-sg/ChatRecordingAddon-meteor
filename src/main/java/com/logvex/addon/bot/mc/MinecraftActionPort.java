package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.ActionPorts;
import com.logvex.addon.bot.core.BlockPos3;
import com.logvex.addon.framework.utils.player.FindItemResult;
import com.logvex.addon.framework.utils.player.InvUtils;
import com.logvex.addon.framework.utils.world.BlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * {@link ActionPorts} backed by the live client.
 *
 * <p>Mining and placing go through the addon's helpers, which send the vanilla interaction; the result
 * is then confirmed from the world, never from the fact that a packet was sent. Crafting and
 * smelting move items with ordinary slot clicks and then trigger the vanilla recipe click, so the
 * server performs the craft and the client only mirrors it.
 */
public final class MinecraftActionPort implements ActionPorts {
    /** How long a placement may stay unconfirmed before it is treated as failed. */
    private static final long PLACE_TIMEOUT_MS = 1500;

    private long placeStartedAt;
    private BlockPos placingPos;

    @Override
    public boolean mine(BlockPos3 pos) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || pos == null) {
            return false;
        }
        BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!mc.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) return false;
        BlockState state = mc.world.getBlockState(p);
        if (state.isAir()) return true; // Already gone: the desired effect holds.

        // Vanilla break: attackBlock starts it, updateBlockBreakingProgress advances it, and the
        // server removes the block when the accumulated progress is enough.
        if (mc.interactionManager.isBreakingBlock()) {
            mc.interactionManager.updateBlockBreakingProgress(p, BlockUtils.getDirection(p));
        } else {
            mc.interactionManager.attackBlock(p, BlockUtils.getDirection(p));
        }
        if (mc.player != null) mc.player.swingHand(Hand.MAIN_HAND);
        return mc.world.getBlockState(p).isAir();
    }

    @Override
    public boolean place(BlockPos3 pos, String itemId) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || pos == null) {
            return false;
        }
        BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
        Item item = item(itemId);
        if (item == null) return false;
        FindItemResult found = InvUtils.find(item);
        if (!found.found()) return false;
        boolean started = BlockUtils.place(p, found, true, 50, true, true, true);
        if (started) {
            placingPos = p;
            placeStartedAt = System.currentTimeMillis();
        }
        return started;
    }

    @Override
    public boolean interact(BlockPos3 pos) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || pos == null) {
            return false;
        }
        BlockPos p = new BlockPos(pos.x(), pos.y(), pos.z());
        Direction side = BlockUtils.getDirection(p);
        Vec3d hit = new Vec3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        BlockHitResult result = new BlockHitResult(hit, side, p, false);
        return mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, result).isAccepted();
    }

    @Override
    public boolean useItem() {
        if (mc.player == null || mc.interactionManager == null) return false;
        return mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND).isAccepted();
    }

    @Override
    public boolean craftingReady() {
        return mc.player != null && (mc.player.currentScreenHandler instanceof PlayerScreenHandler
            || mc.player.currentScreenHandler instanceof CraftingScreenHandler);
    }

    @Override
    public boolean craft(String recipeId, int times) {
        if (!craftingReady() || mc.player == null || mc.interactionManager == null) return false;
        Identifier id = Identifier.tryParse(recipeId);
        if (id == null || mc.world == null) return false;

        RecipeEntry<?> entry = mc.world.getRecipeManager().get(id).orElse(null);
        if (entry == null) return false;

        int syncId = mc.player.currentScreenHandler.syncId;
        boolean ok = false;
        for (int i = 0; i < Math.max(1, times); i++) {
            // Ask the recipe book to fill the grid; this only places items already held.
            mc.interactionManager.clickRecipe(syncId, entry, true);
            mc.interactionManager.clickSlot(syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
            ok = true;
        }
        return ok;
    }

    @Override
    public boolean furnaceReady() {
        return mc.player != null && mc.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler;
    }

    @Override
    public boolean smelt(String inputId, String fuelId, int count) {
        if (!furnaceReady() || mc.player == null || mc.interactionManager == null) return false;
        Item input = item(inputId);
        Item fuel = item(fuelId);
        if (input == null || fuel == null) return false;

        var handler = mc.player.currentScreenHandler;
        List<Integer> inputSlots = slotsFor(handler, input);
        List<Integer> fuelSlots = slotsFor(handler, fuel);
        if (inputSlots.isEmpty()) return false;

        int inputSlot = inputSlots.get(0);
        int syncId = handler.syncId;
        for (int i = 0; i < Math.max(1, count); i++) {
            mc.interactionManager.clickSlot(syncId, inputSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
        }
        if (!fuelSlots.isEmpty()) {
            mc.interactionManager.clickSlot(syncId, fuelSlots.get(0), 0, SlotActionType.QUICK_MOVE, mc.player);
        }
        return true;
    }

    @Override
    public boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null
            && mc.player.isAlive() && !mc.player.isRemoved();
    }

    @Override
    public void cancel() {
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        placingPos = null;
    }

    /** True when the placement started earlier has now appeared in the world, or timed out. */
    public boolean placementResolved(boolean targetPresent) {
        if (placingPos == null) return true;
        if (targetPresent) {
            placingPos = null;
            return true;
        }
        if (System.currentTimeMillis() - placeStartedAt > PLACE_TIMEOUT_MS) {
            placingPos = null;
            return true;
        }
        return false;
    }

    /** The slot indices of the open handler that hold {@code item}, in the main inventory area. */
    private static List<Integer> slotsFor(net.minecraft.screen.ScreenHandler handler, Item item) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && stack.isOf(item)) out.add(i);
        }
        return out;
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }

    /** True when the crafting grid belongs to a real recipe handler (for the crafting executor). */
    public static boolean hasRecipeGrid(net.minecraft.screen.ScreenHandler handler) {
        return handler instanceof AbstractRecipeScreenHandler<?, ?>;
    }
}
