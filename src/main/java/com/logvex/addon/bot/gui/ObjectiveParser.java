package com.logvex.addon.bot.gui;

import java.util.Locale;

/**
 * Parses the short objective line a user types into the dashboard, e.g.
 * {@code gather 256 oak_log} or {@code craft 8 chest}.
 *
 * <p>Deliberately small and forgiving: the first word may be a verb the planner knows, the last may
 * be an item id, and a number in between is the amount. When nothing sensible can be read the
 * parser returns {@code null} so the caller can explain the accepted shape instead of queueing a
 * task that does nothing.
 */
final class ObjectiveParser {
    private ObjectiveParser() {
    }

    /** A parsed objective, ready for {@code BotModule.addGoal}. */
    record Parsed(String description, String type, String itemId, int amount) {
    }

    static Parsed parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;

        String[] words = text.split("\\s+");
        String type = "";
        String itemId = "";
        int amount = 1;

        for (String word : words) {
            if (word.isEmpty()) continue;
            if (isNumber(word)) {
                amount = clampAmount(word);
                continue;
            }
            if (type.isEmpty() && com.logvex.addon.bot.core.TaskPlanner.typeFor(word) != null) {
                type = word.toLowerCase(Locale.ROOT);
                continue;
            }
            if (itemId.isEmpty()) itemId = normaliseId(word);
        }

        // "256 oak_log" with no verb is a gather objective; "oak_log" alone gathers one.
        if (type.isEmpty() && !itemId.isEmpty()) type = "gather";
        if (itemId.isEmpty() && type.isEmpty()) return null;
        if (itemId.isEmpty()) return null;

        return new Parsed(text, type, itemId, amount);
    }

    private static boolean isNumber(String word) {
        if (word.isEmpty()) return false;
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) return false;
        }
        return true;
    }

    private static int clampAmount(String word) {
        try {
            return Math.max(1, Math.min(1_000_000, Integer.parseInt(word)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String normaliseId(String word) {
        String value = word.toLowerCase(Locale.ROOT).replace(' ', '_');
        return value.indexOf(':') < 0 ? "minecraft:" + value : value;
    }
}
