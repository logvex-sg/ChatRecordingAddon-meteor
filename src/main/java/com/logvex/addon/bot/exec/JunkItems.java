package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.InventorySnapshot;
import com.logvex.addon.bot.core.ItemStackView;

import java.util.List;
import java.util.Map;

/**
 * The blocks a bot is allowed to throw away.
 *
 * <p>This is a whitelist, not a blacklist: anything absent is kept. A full inventory is an
 * inconvenience, but a discarded tool, ore or named item is a real loss, so the safe default is to
 * keep. Both the tidy task and the make-room path share this one list rather than each keeping a
 * copy that could drift apart.
 */
final class JunkItems {
    private static final List<String> JUNK = List.of(
        "minecraft:cobblestone",
        "minecraft:cobbled_deepslate",
        "minecraft:dirt",
        "minecraft:coarse_dirt",
        "minecraft:rooted_dirt",
        "minecraft:gravel",
        "minecraft:flint",
        "minecraft:andesite",
        "minecraft:diorite",
        "minecraft:granite",
        "minecraft:tuff",
        "minecraft:netherrack",
        "minecraft:basalt",
        "minecraft:blackstone",
        "minecraft:sand",
        "minecraft:red_sand",
        "minecraft:sandstone",
        "minecraft:stone",
        "minecraft:snowball",
        "minecraft:kelp",
        "minecraft:seagrass");

    private JunkItems() {
    }

    static boolean isJunk(String itemId) {
        return itemId != null && JUNK.contains(itemId);
    }

    /**
     * The junk item occupying the most slots, or empty when nothing may be dropped.
     *
     * <p>Dropping the biggest holding frees the most room per interaction.
     */
    static String biggest(InventorySnapshot snapshot) {
        String best = "";
        int bestCount = 0;
        for (Map.Entry<Integer, ItemStackView> entry : snapshot.slots().entrySet()) {
            ItemStackView stack = entry.getValue();
            if (stack.empty() || !isJunk(stack.itemId())) continue;
            int total = snapshot.countOf(stack.itemId());
            if (total > bestCount) {
                bestCount = total;
                best = stack.itemId();
            }
        }
        return best;
    }
}
