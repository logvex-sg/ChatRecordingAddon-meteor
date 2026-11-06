package com.logvex.addon.framework.utils.world;

import com.logvex.addon.framework.utils.player.FindItemResult;
import com.logvex.addon.framework.utils.player.InvUtils;
import com.logvex.addon.framework.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Block interaction built on the vanilla interaction manager.
 *
 * <p>{@link #place} selects the item, aims at a face of the target, and sends a use interaction —
 * exactly what a player click does. Nothing here writes to the world directly, so a placement is
 * only real once the server confirms it.
 */
public final class BlockUtils {
    private BlockUtils() {
    }

    public static boolean place(BlockPos blockPos, FindItemResult findItemResult, boolean rotate,
                                int rotationPriority, boolean swingHand, boolean checkEntities, boolean swapBack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (findItemResult == null || !findItemResult.found()) return false;

        Hand hand = findItemResult.getHand();
        int slot = findItemResult.slot();
        if (hand == null) return false;

        ItemStack stack = hand == Hand.MAIN_HAND
            ? mc.player.getInventory().getStack(slot)
            : mc.player.getOffHandStack();
        Block toPlace = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : Blocks.OBSIDIAN;
        if (!canPlaceBlock(blockPos, checkEntities, toPlace)) return false;

        Vec3d hitPos = Vec3d.ofCenter(blockPos);
        Direction side = getPlaceSide(blockPos);
        BlockPos neighbour;
        if (side == null) {
            side = Direction.UP;
            neighbour = blockPos;
        } else {
            neighbour = blockPos.offset(side);
            hitPos = hitPos.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        }

        BlockHitResult hit = new BlockHitResult(hitPos, side.getOpposite(), neighbour, false);

        if (rotate) {
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), rotationPriority, () -> {
                if (hand == Hand.MAIN_HAND) InvUtils.swap(slot, swapBack);
                interact(hit, hand, swingHand);
                if (swapBack) InvUtils.swapBack();
            });
        } else {
            if (hand == Hand.MAIN_HAND) InvUtils.swap(slot, swapBack);
            interact(hit, hand, swingHand);
            if (swapBack) InvUtils.swapBack();
        }
        return true;
    }

    public static void interact(BlockHitResult hit, Hand hand, boolean swing) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;

        boolean wasSneaking = mc.player.input.sneaking;
        mc.player.input.sneaking = false;
        ActionResult result = mc.interactionManager.interactBlock(mc.player, hand, hit);
        if (result.shouldSwingHand()) {
            if (swing) mc.player.swingHand(hand);
            else if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
        mc.player.input.sneaking = wasSneaking;
    }

    public static boolean canPlaceBlock(BlockPos blockPos, boolean checkEntities, Block block) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (blockPos == null || mc.world == null) return false;
        if (!World.isValid(blockPos)) return false;
        if (!mc.world.getBlockState(blockPos).isReplaceable()) return false;
        return !checkEntities || mc.world.canPlace(block.getDefaultState(), blockPos, net.minecraft.block.ShapeContext.absent());
    }

    /** The face the player is most likely able to reach, or {@code null} to place against the block itself. */
    public static Direction getPlaceSide(BlockPos blockPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;

        Vec3d lookVec = blockPos.toCenterPos().subtract(mc.player.getEyePos());
        double bestRelevancy = -Double.MAX_VALUE;
        Direction bestSide = null;

        for (Direction side : Direction.values()) {
            BlockPos neighbour = blockPos.offset(side);
            var state = mc.world.getBlockState(neighbour);
            if (state.isAir() || !state.isSolidBlock(mc.world, neighbour)) continue;

            double relevancy = side.getAxis().choose(lookVec.getX(), lookVec.getY(), lookVec.getZ()) * side.getDirection().offset();
            if (relevancy > bestRelevancy) {
                bestRelevancy = relevancy;
                bestSide = side;
            }
        }
        return bestSide;
    }

    /** The direction the player should aim at to break the block. */
    public static Direction getDirection(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return Direction.UP;

        Vec3d eyes = new Vec3d(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()), mc.player.getZ());
        if (pos.getY() > eyes.y) {
            if (mc.world.getBlockState(pos.add(0, -1, 0)).isReplaceable()) return Direction.DOWN;
            return mc.player.getHorizontalFacing().getOpposite();
        }
        if (!mc.world.getBlockState(pos.add(0, 1, 0)).isReplaceable()) {
            return mc.player.getHorizontalFacing().getOpposite();
        }
        return Direction.UP;
    }
}
