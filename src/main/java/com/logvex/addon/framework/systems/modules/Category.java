package com.logvex.addon.framework.systems.modules;

import net.minecraft.item.ItemStack;

/** A module category, used to group modules in the UI. */
public class Category {
    public final String name;
    public final ItemStack icon;
    private final int nameHash;

    public Category(String name, ItemStack icon) {
        this.name = name;
        this.nameHash = name.hashCode();
        // Deliberately not defaulted to an item stack: touching Items here would force the whole
        // item registry to bootstrap, which must not happen before the game is ready.
        this.icon = icon;
    }

    public Category(String name) {
        this(name, null);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Category category)) return false;
        return nameHash == category.nameHash;
    }

    @Override
    public int hashCode() {
        return nameHash;
    }
}
