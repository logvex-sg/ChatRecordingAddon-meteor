package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.BlockCategory;
import com.logvex.addon.schematicdropper.core.BlockTraits;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.block.RailBlock;
import net.minecraft.block.RedstoneTorchBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.TorchBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.TripwireBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * {@link BlockTraits} backed by the real Minecraft 1.21.1 block classes.
 *
 * <p>Classification uses {@code instanceof} against the vanilla block classes (and the vanilla
 * {@code BlockState.isReplaceable} and fluid tests) rather than string matching, so the ordering and
 * support rules follow verified game behaviour. The class is deliberately stateless so it can be
 * shared by the solver, the comparison and the placement engine.
 */
public final class MinecraftBlockTraits implements BlockTraits {
    @Override
    public boolean isAir(String blockId) {
        Block block = block(blockId);
        if (block == null) return false;
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR;
    }

    @Override
    public boolean isFluid(String blockId) {
        Block block = block(blockId);
        if (block == null) return false;
        return block == Blocks.WATER || block == Blocks.LAVA
            || block == Blocks.BUBBLE_COLUMN || block == Blocks.KELP || block == Blocks.KELP_PLANT
            || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS;
    }

    @Override
    public boolean isReplaceable(String blockId) {
        if (isAir(blockId)) return true;
        Block block = block(blockId);
        if (block == null) return false;
        // Vanilla's own notion: grass, snow layers, water, lava, plants and similar.
        return block.getDefaultState().isReplaceable();
    }

    @Override
    public boolean isGravityAffected(String blockId) {
        Block block = block(blockId);
        return block instanceof FallingBlock;
    }

    @Override
    public boolean requiresSupport(String blockId) {
        Block block = block(blockId);
        if (block == null) return false;
        return block instanceof TorchBlock
            || block instanceof PlantBlock
            || block instanceof CarpetBlock
            || block instanceof RailBlock
            || block instanceof RedstoneWireBlock
            || block instanceof RedstoneTorchBlock
            || block instanceof LeverBlock
            || block instanceof LadderBlock
            || block instanceof WallMountedBlock
            || block instanceof SnowBlock
            || block instanceof TripwireBlock
            || block instanceof PressurePlateBlock
            || block instanceof ButtonBlock;
    }

    @Override
    public boolean isDirectional(String blockId) {
        Block block = block(blockId);
        if (block == null) return false;
        return block instanceof StairsBlock
            || block instanceof SlabBlock
            || block instanceof DoorBlock
            || block instanceof TrapdoorBlock
            || block instanceof FenceBlock
            || block instanceof FenceGateBlock
            || block instanceof PaneBlock
            || block instanceof WallBlock
            || block instanceof WallMountedBlock
            || block instanceof PistonBlock;
    }

    @Override
    public boolean isContainer(String blockId) {
        Block block = block(blockId);
        if (block == null) return false;
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof ShulkerBoxBlock
            || block instanceof DispenserBlock
            || block instanceof HopperBlock;
    }

    @Override
    public boolean isRedstoneComponent(String blockId) {
        Block block = block(blockId);
        if (block == null) return false;
        return block instanceof RedstoneWireBlock
            || block instanceof RedstoneTorchBlock
            || block instanceof LeverBlock
            || block instanceof PressurePlateBlock
            || block instanceof ButtonBlock
            || block instanceof TripwireBlock
            || block instanceof PistonBlock;
    }

    @Override
    public boolean hasSpecialPlacement(String blockId) {
        Block block = block(blockId);
        if (block == null) return false;
        // Doors, beds and tall plants occupy a second cell the schematic must also provide.
        return block instanceof DoorBlock
            || block instanceof BedBlock
            || block instanceof TallPlantBlock
            || block instanceof ScaffoldingBlock
            || block instanceof AnvilBlock;
    }

    @Override
    public boolean isValid(String blockId) {
        if (blockId == null || blockId.isBlank()) return false;
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) return false;
        Block block = Registries.BLOCK.get(id);
        return block != Blocks.AIR || blockId.equals("minecraft:air");
    }

    @Override
    public BlockCategory category(String blockId) {
        if (isAir(blockId)) return BlockCategory.AIR;
        if (isFluid(blockId)) return BlockCategory.FLUID;
        if (isGravityAffected(blockId)) return BlockCategory.GRAVITY;
        if (requiresSupport(blockId)) return BlockCategory.ATTACHED;

        Block block = block(blockId);
        if (block == null) return BlockCategory.OTHER;
        if (block instanceof SlabBlock) return BlockCategory.SLAB;
        if (block instanceof StairsBlock) return BlockCategory.STAIRS;
        if (block instanceof DoorBlock) return BlockCategory.DOOR;
        if (block instanceof TrapdoorBlock) return BlockCategory.TRAPDOOR;
        if (block instanceof FenceBlock) return BlockCategory.FENCE;
        if (block instanceof FenceGateBlock) return BlockCategory.FENCE_GATE;
        if (block instanceof WallBlock) return BlockCategory.WALL;
        if (block instanceof PaneBlock) return BlockCategory.PANE;
        if (isRedstoneComponent(blockId)) return BlockCategory.REDSTONE;
        if (isContainer(blockId)) return BlockCategory.CONTAINER;
        if (block instanceof PistonBlock) return BlockCategory.PISTON;
        if (isReplaceable(blockId)) return BlockCategory.OTHER;
        return BlockCategory.SIMPLE;
    }

    @Override
    public String displayName(String blockId) {
        Block block = block(blockId);
        if (block == null) return com.logvex.addon.common.Messages.prettyId(blockId);
        return block.getName().getString();
    }

    @Override
    public int stackLimit(String blockId) {
        Block block = block(blockId);
        if (block == null) return 64;
        Item item = block.asItem();
        if (item == null) return 64;
        return Math.max(1, new ItemStack(item).getMaxCount());
    }

    // --------------------------------------------------------------- adapters

    /** Converts a live block state into the Minecraft-free spec. */
    public static com.logvex.addon.schematicdropper.core.BlockStateSpec toSpec(net.minecraft.block.BlockState state) {
        if (state == null) return com.logvex.addon.schematicdropper.core.BlockStateSpec.air();
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<net.minecraft.state.property.Property<?>, Comparable<?>> e : state.getEntries().entrySet()) {
            props.put(e.getKey().getName(), nameOf(e.getValue()));
        }
        return com.logvex.addon.schematicdropper.core.BlockStateSpec.of(id.toString(), props);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String nameOf(Comparable<?> value) {
        if (value instanceof net.minecraft.state.property.Property) return "";
        return String.valueOf(value);
    }

    /**
     * Resolves a spec to a live block state, applying every property the schematic declares.
     * Unknown or invalid property values are skipped so a state still resolves.
     */
    public static net.minecraft.block.BlockState toState(com.logvex.addon.schematicdropper.core.BlockStateSpec spec) {
        Block block = block(spec.id());
        if (block == null) return null;

        net.minecraft.block.BlockState state = block.getDefaultState();
        for (java.util.Map.Entry<String, String> e : spec.properties().entrySet()) {
            state = applyProperty(state, e.getKey(), e.getValue());
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static net.minecraft.block.BlockState applyProperty(net.minecraft.block.BlockState state,
                                                                String name, String value) {
        for (net.minecraft.state.property.Property property : state.getProperties()) {
            if (!property.getName().equals(name)) continue;
            java.util.Optional<?> parsed = property.parse(value);
            if (parsed.isPresent()) {
                net.minecraft.state.State raw = state;
                return (net.minecraft.block.BlockState) raw.with(property, (Comparable) parsed.get());
            }
        }
        return state;
    }

    private static Block block(String blockId) {
        if (blockId == null || blockId.isBlank()) return null;
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) return null;
        return Registries.BLOCK.get(id);
    }

    /** Reads a live world block, or air when the world is unavailable. */
    public static com.logvex.addon.schematicdropper.core.BlockStateSpec worldState(BlockPos pos) {
        if (mc.world == null || pos == null) return com.logvex.addon.schematicdropper.core.BlockStateSpec.air();
        return toSpec(mc.world.getBlockState(pos));
    }

    public static boolean isLoaded(BlockPos pos) {
        return mc.world != null && pos != null && mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isReplaceableAt(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        return mc.world.getBlockState(pos).isReplaceable();
    }

    public static BlockPos toBlockPos(com.logvex.addon.schematicdropper.core.BlockPos3 pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    public static com.logvex.addon.schematicdropper.core.BlockPos3 fromBlockPos(BlockPos pos) {
        return new com.logvex.addon.schematicdropper.core.BlockPos3(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Converts a live NBT compound into the Minecraft-free tree the schematic readers consume. */
    public static com.logvex.addon.schematicdropper.core.NbtNode toNode(net.minecraft.nbt.NbtElement element) {
        return NbtBridge.toNode(element);
    }

    /** The world's minimum build height, used for the range and bounds checks. */
    public static int minBuildHeight() {
        return mc.world == null ? World.MIN_Y : mc.world.getBottomY();
    }
}
