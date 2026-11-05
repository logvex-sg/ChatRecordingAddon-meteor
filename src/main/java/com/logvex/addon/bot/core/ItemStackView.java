package com.logvex.addon.bot.core;

/** A Minecraft-free view of one inventory slot. */
public record ItemStackView(String itemId, int count, int maxCount, int damage, int maxDamage) {
    public boolean empty() {
        return itemId == null || itemId.isEmpty() || count <= 0;
    }

    public boolean isOf(String id) {
        return itemId != null && itemId.equals(id);
    }

    /** Remaining durability, or {@code -1} when the item is not damageable. */
    public int durabilityLeft() {
        if (maxDamage <= 0) return -1;
        return Math.max(0, maxDamage - damage);
    }

    public double durabilityFraction() {
        int left = durabilityLeft();
        return left < 0 ? 1.0 : (double) left / maxDamage;
    }

    public static ItemStackView blank() {
        return new ItemStackView("", 0, 64, 0, 0);
    }
}
