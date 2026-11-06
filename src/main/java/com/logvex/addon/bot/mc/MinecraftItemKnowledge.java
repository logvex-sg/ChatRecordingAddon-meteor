package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.ItemKnowledge;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.logvex.addon.framework.Addon.mc;

/**
 * {@link ItemKnowledge} backed by the live Minecraft registries.
 *
 * <p>Tag lookups are memoised because the planner asks the same tag repeatedly and a registry walk
 * on every question would be wasteful. The cache is cleared on a resource reload through
 * {@link #clearCache()}.
 */
public final class MinecraftItemKnowledge implements ItemKnowledge {
    private final Map<String, List<String>> tagCache = new HashMap<>();
    private final Map<String, List<String>> yieldCache = new HashMap<>();

    public void clearCache() {
        tagCache.clear();
        yieldCache.clear();
    }

    @Override
    public List<String> itemsInTag(String tag) {
        if (tag == null || tag.isEmpty()) return List.of();
        List<String> cached = tagCache.get(tag);
        if (cached != null) return cached;

        List<String> out = new ArrayList<>();
        Identifier id = Identifier.tryParse(tag);
        if (id != null && mc.world != null) {
            TagKey<Item> key = TagKey.of(RegistryKeys.ITEM, id);
            for (Item item : Registries.ITEM) {
                if (Registries.ITEM.getEntry(item).isIn(key)) {
                    out.add(Registries.ITEM.getId(item).toString());
                }
            }
        }
        tagCache.put(tag, out);
        return out;
    }

    @Override
    public List<String> toolsFor(String blockId) {
        // Prefer the block's own item as the tool; the helper's findFastestTool handles the real
        // choice at placement time, so an exhaustive tool table is not needed here.
        return List.of(blockId);
    }

    @Override
    public boolean isLog(String itemId) {
        if (itemId == null) return false;
        String path = path(itemId);
        return path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_wood")
            || path.endsWith("_hyphae");
    }

    @Override
    public boolean isContainer(String itemId) {
        if (!(item(itemId) instanceof BlockItem)) return false;
        String path = path(itemId);
        return path.endsWith("_chest") || path.endsWith("_barrel") || path.endsWith("_shulker_box")
            || path.endsWith("_furnace") || path.endsWith("_hopper") || path.endsWith("_dispenser")
            || path.endsWith("_dropper") || path.endsWith("_crafter");
    }

    @Override
    public boolean isSeed(String itemId) {
        if (itemId == null) return false;
        String path = path(itemId);
        return path.endsWith("_seeds") || path.equals("carrot") || path.equals("potato")
            || path.equals("beetroot_seeds") || path.equals("nether_wart");
    }

    @Override
    public String cropForSeed(String seedId) {
        if (seedId == null) return "";
        String path = path(seedId);
        return switch (path) {
            case "wheat_seeds" -> "minecraft:wheat";
            case "beetroot_seeds" -> "minecraft:beetroots";
            case "carrot" -> "minecraft:carrots";
            case "potato" -> "minecraft:potatoes";
            case "nether_wart" -> "minecraft:nether_wart";
            default -> "";
        };
    }

    @Override
    public List<String> blocksYielding(String itemId) {
        if (itemId == null || itemId.isEmpty()) return List.of();
        List<String> cached = yieldCache.get(itemId);
        if (cached != null) return cached;

        List<String> out = new ArrayList<>();
        // A block with the same id as the item yields it directly (logs, stone, ores as blocks).
        if (Registries.BLOCK.containsId(Identifier.tryParse(itemId))) out.add(itemId);

        // An item that is not a block but is dropped by a block: find any block whose item is this
        // item, plus the crop that grows it.
        for (Block block : Registries.BLOCK) {
            BlockState state = block.getDefaultState();
            Item asItem = state.getBlock().asItem();
            if (asItem != null && !asItem.equals(net.minecraft.item.Items.AIR)
                && Registries.ITEM.getId(asItem).toString().equals(itemId)
                && !out.contains(Registries.BLOCK.getId(block).toString())) {
                out.add(Registries.BLOCK.getId(block).toString());
            }
        }
        if (out.isEmpty()) out.add(itemId);
        yieldCache.put(itemId, out);
        return out;
    }

    @Override
    public List<String> matureCropsFor(String itemId) {
        List<String> out = new ArrayList<>();
        if (itemId == null || itemId.isEmpty()) return out;
        for (Block block : Registries.BLOCK) {
            if (block instanceof CropBlock) {
                Item seed = block.asItem();
                if (seed != null && !seed.equals(net.minecraft.item.Items.AIR)
                    && Registries.ITEM.getId(seed).toString().equals(itemId)) {
                    out.add(Registries.BLOCK.getId(block).toString());
                }
            }
        }
        if (out.isEmpty() && Registries.BLOCK.containsId(Identifier.tryParse(itemId))) out.add(itemId);
        return out;
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        return id == null ? null : Registries.ITEM.get(id);
    }

    private static String path(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? itemId : itemId.substring(colon + 1);
    }
}
