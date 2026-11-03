package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.BlockPos3;
import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.PlacementEnv;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * {@link PlacementEnv} backed by the live client world.
 *
 * <p>Read-only: it never changes the world or the inventory. The placement engine uses it both to
 * decide what to do and to verify what actually happened, so every value here reflects the server's
 * view as far as the client can see it.
 */
public final class MinecraftPlacementEnv implements PlacementEnv {
    @Override
    public boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null;
    }

    @Override
    public boolean canPlace() {
        if (mc.player == null) return false;
        return mc.currentScreen == null && mc.player.isAlive() && !mc.player.isRemoved();
    }

    @Override
    public BlockStateSpec stateAt(BlockPos3 pos) {
        if (mc.world == null || pos == null) return BlockStateSpec.air();
        BlockState state = mc.world.getBlockState(MinecraftBlockTraits.toBlockPos(pos));
        return MinecraftBlockTraits.toSpec(state);
    }

    @Override
    public boolean isLoaded(BlockPos3 pos) {
        if (mc.world == null || pos == null) return false;
        BlockPos p = MinecraftBlockTraits.toBlockPos(pos);
        return mc.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4);
    }

    @Override
    public boolean isReplaceable(BlockPos3 pos) {
        if (mc.world == null || pos == null) return false;
        return mc.world.getBlockState(MinecraftBlockTraits.toBlockPos(pos)).isReplaceable();
    }

    @Override
    public boolean isAirAt(BlockPos3 pos) {
        return stateAt(pos).isAir();
    }

    @Override
    public int countOf(String itemId) {
        if (mc.player == null) return 0;
        Item item = item(itemId);
        if (item == null) return 0;

        int total = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    @Override
    public boolean isCreative() {
        return mc.interactionManager != null && mc.interactionManager.hasCreativeInventory();
    }

    @Override
    public double[] playerPosition() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return new double[]{0, 0, 0};
        return new double[]{player.getX(), player.getEyeY(), player.getZ()};
    }

    @Override
    public double distanceSquaredTo(BlockPos3 pos) {
        ClientPlayerEntity player = mc.player;
        if (player == null || pos == null) return -1;
        double dx = pos.x() + 0.5 - player.getX();
        double dy = pos.y() + 0.5 - player.getEyeY();
        double dz = pos.z() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }
}
