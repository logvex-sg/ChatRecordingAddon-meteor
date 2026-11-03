package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.BlockStateSpec;
import com.logvex.addon.schematicdropper.core.LegacyBlockIds;

/**
 * A small, honest mapping from pre-flattening block ids to modern block states.
 *
 * <p>Legacy ({@code .schematic}) files store numeric ids, and the full flattening table is not part
 * of the client. Rather than guessing, this table covers the ids that appear in practice and returns
 * {@code null} for anything else, so an unsupported block is reported as invalid instead of silently
 * becoming the wrong block. Metadata selects the matching variant for the families that use it
 * (planks, logs, wool, stone bricks and similar).
 */
public final class LegacyBlockIdTable implements LegacyBlockIds {
    @Override
    public BlockStateSpec resolve(int id, int meta) {
        return switch (id) {
            case 1 -> meta == 0 ? spec("minecraft:stone") : switch (meta) {
                case 1 -> spec("minecraft:granite");
                case 2 -> spec("minecraft:polished_granite");
                case 3 -> spec("minecraft:diorite");
                case 4 -> spec("minecraft:polished_diorite");
                case 5 -> spec("minecraft:andesite");
                case 6 -> spec("minecraft:polished_andesite");
                default -> spec("minecraft:stone");
            };
            case 2 -> spec("minecraft:grass_block");
            case 3 -> switch (meta) {
                case 1 -> spec("minecraft:coarse_dirt");
                case 2 -> spec("minecraft:podzol");
                default -> spec("minecraft:dirt");
            };
            case 4 -> spec("minecraft:cobblestone");
            case 5 -> planks(meta);
            case 7 -> spec("minecraft:bedrock");
            case 8, 9 -> spec("minecraft:water");
            case 10, 11 -> spec("minecraft:lava");
            case 12 -> spec("minecraft:sand");
            case 13 -> spec("minecraft:gravel");
            case 14 -> spec("minecraft:gold_ore");
            case 15 -> spec("minecraft:iron_ore");
            case 16 -> spec("minecraft:coal_ore");
            case 17 -> log(meta);
            case 18 -> leaves(meta);
            case 19 -> spec("minecraft:sponge");
            case 20 -> spec("minecraft:glass");
            case 21 -> spec("minecraft:lapis_ore");
            case 22 -> spec("minecraft:lapis_block");
            case 23 -> spec("minecraft:dispenser");
            case 24 -> sandstone(meta);
            case 25 -> spec("minecraft:note_block");
            case 35 -> wool(meta);
            case 41 -> spec("minecraft:gold_block");
            case 42 -> spec("minecraft:iron_block");
            case 43, 44 -> spec(meta % 2 == 0 ? "minecraft:stone_slab" : "minecraft:smooth_stone_slab");
            case 45 -> spec("minecraft:bricks");
            case 46 -> spec("minecraft:tnt");
            case 47 -> spec("minecraft:bookshelf");
            case 48 -> spec("minecraft:mossy_cobblestone");
            case 49 -> spec("minecraft:obsidian");
            case 50 -> spec("minecraft:torch");
            case 52 -> spec("minecraft:spawner");
            case 53 -> spec("minecraft:oak_stairs");
            case 54 -> spec("minecraft:chest");
            case 55 -> spec("minecraft:redstone_wire");
            case 56 -> spec("minecraft:diamond_ore");
            case 57 -> spec("minecraft:diamond_block");
            case 58 -> spec("minecraft:crafting_table");
            case 60 -> spec("minecraft:farmland");
            case 61, 62 -> spec("minecraft:furnace");
            case 65 -> spec("minecraft:ladder");
            case 66 -> spec("minecraft:rail");
            case 67 -> spec("minecraft:stone_stairs");
            case 73, 74 -> spec("minecraft:redstone_ore");
            case 78 -> spec("minecraft:snow");
            case 79 -> spec("minecraft:ice");
            case 80 -> spec("minecraft:snow_block");
            case 82 -> spec("minecraft:clay");
            case 83 -> spec("minecraft:sugar_cane");
            case 85 -> spec("minecraft:oak_fence");
            case 87 -> spec("minecraft:netherrack");
            case 88 -> spec("minecraft:soul_sand");
            case 89 -> spec("minecraft:glowstone");
            case 95 -> stainedGlass(meta);
            case 98 -> stoneBrick(meta);
            case 102 -> spec("minecraft:glass_pane");
            case 103 -> spec("minecraft:melon");
            case 110 -> spec("minecraft:mycelium");
            case 112 -> spec("minecraft:nether_bricks");
            case 121 -> spec("minecraft:end_stone");
            case 129 -> spec("minecraft:emerald_ore");
            case 133 -> spec("minecraft:emerald_block");
            case 137 -> spec("minecraft:command_block");
            case 139 -> spec("minecraft:cobblestone_wall");
            case 152 -> spec("minecraft:redstone_block");
            case 153 -> spec("minecraft:nether_quartz_ore");
            case 155 -> quartz(meta);
            case 156 -> spec("minecraft:quartz_stairs");
            case 159 -> stainedClay(meta);
            case 162 -> log2(meta);
            case 168 -> spec("minecraft:prismarine");
            case 172 -> spec("minecraft:terracotta");
            case 179 -> spec("minecraft:red_sandstone");
            case 201 -> spec("minecraft:purpur_block");
            case 251 -> spec("minecraft:white_concrete");
            case 252 -> spec("minecraft:white_concrete_powder");
            default -> null;
        };
    }

    private static BlockStateSpec spec(String id) {
        return BlockStateSpec.of(id);
    }

    private static BlockStateSpec planks(int meta) {
        return switch (meta & 0x7) {
            case 0 -> spec("minecraft:oak_planks");
            case 1 -> spec("minecraft:spruce_planks");
            case 2 -> spec("minecraft:birch_planks");
            case 3 -> spec("minecraft:jungle_planks");
            case 4 -> spec("minecraft:acacia_planks");
            case 5 -> spec("minecraft:dark_oak_planks");
            default -> spec("minecraft:oak_planks");
        };
    }

    private static BlockStateSpec log(int meta) {
        return switch (meta & 0x3) {
            case 0 -> spec("minecraft:oak_log");
            case 1 -> spec("minecraft:spruce_log");
            case 2 -> spec("minecraft:birch_log");
            default -> spec("minecraft:jungle_log");
        };
    }

    private static BlockStateSpec log2(int meta) {
        return switch (meta & 0x3) {
            case 0 -> spec("minecraft:acacia_log");
            default -> spec("minecraft:dark_oak_log");
        };
    }

    private static BlockStateSpec leaves(int meta) {
        return switch (meta & 0x3) {
            case 0 -> spec("minecraft:oak_leaves");
            case 1 -> spec("minecraft:spruce_leaves");
            case 2 -> spec("minecraft:birch_leaves");
            default -> spec("minecraft:jungle_leaves");
        };
    }

    private static BlockStateSpec sandstone(int meta) {
        return meta == 2 ? spec("minecraft:cut_sandstone") : spec("minecraft:sandstone");
    }

    private static BlockStateSpec stoneBrick(int meta) {
        return switch (meta) {
            case 1 -> spec("minecraft:mossy_stone_bricks");
            case 2 -> spec("minecraft:cracked_stone_bricks");
            case 3 -> spec("minecraft:chiseled_stone_bricks");
            default -> spec("minecraft:stone_bricks");
        };
    }

    private static BlockStateSpec quartz(int meta) {
        return switch (meta) {
            case 1 -> spec("minecraft:chiseled_quartz_block");
            case 2 -> spec("minecraft:quartz_pillar");
            default -> spec("minecraft:quartz_block");
        };
    }

    private static BlockStateSpec wool(int meta) {
        return spec(colour(meta) + "_wool");
    }

    private static BlockStateSpec stainedGlass(int meta) {
        return spec(colour(meta) + "_stained_glass");
    }

    private static BlockStateSpec stainedClay(int meta) {
        return spec(colour(meta) + "_terracotta");
    }

    private static String colour(int meta) {
        return switch (meta & 0xF) {
            case 0 -> "white";
            case 1 -> "orange";
            case 2 -> "magenta";
            case 3 -> "light_blue";
            case 4 -> "yellow";
            case 5 -> "lime";
            case 6 -> "pink";
            case 7 -> "gray";
            case 8 -> "light_gray";
            case 9 -> "cyan";
            case 10 -> "purple";
            case 11 -> "blue";
            case 12 -> "brown";
            case 13 -> "green";
            case 14 -> "red";
            default -> "black";
        };
    }
}
