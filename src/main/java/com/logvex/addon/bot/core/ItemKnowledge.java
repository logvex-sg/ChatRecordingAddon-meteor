package com.logvex.addon.bot.core;

import java.util.List;

/**
 * Registry knowledge the planner needs but cannot derive from ids alone: which items satisfy a
 * tag, which blocks yield an item, and which crops are mature.
 *
 * <p>Kept behind an interface because the real answers come from Minecraft's registries, which are
 * unavailable in a unit test. The Minecraft layer supplies a registry-backed implementation; tests
 * supply a fixed one.
 */
public interface ItemKnowledge {
    /** Every item id that belongs to {@code tag}, e.g. {@code minecraft:logs}. */
    List<String> itemsInTag(String tag);

    /** True when {@code itemId} belongs to {@code tag}. */
    default boolean isInTag(String tag, String itemId) {
        return itemsInTag(tag).contains(itemId);
    }

    /** The item ids of the tools that effectively break {@code blockId}, best first. */
    List<String> toolsFor(String blockId);

    /** True when {@code itemId} is a log/stem block that yields planks. */
    boolean isLog(String itemId);

    /** True when {@code itemId} is a placeable storage container. */
    boolean isContainer(String itemId);

    /** True when {@code itemId} is a seed that can be replanted. */
    boolean isSeed(String itemId);

    /** The block a seed plants, or empty. */
    default String cropForSeed(String seedId) {
        return "";
    }

    /**
     * The block ids that yield {@code itemId} when mined. A plain block yields itself; an ore yields
     * its drop. The default is the item's own id, which is correct for most placeable blocks.
     */
    default List<String> blocksYielding(String itemId) {
        return List.of(itemId);
    }

    /** The mature crop block ids that yield {@code itemId} when harvested. */
    default List<String> matureCropsFor(String itemId) {
        return List.of(itemId);
    }
}
