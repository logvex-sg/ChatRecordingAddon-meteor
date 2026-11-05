package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of planning a craft: the recipe, how many times it must run, and the total base
 * materials required once every intermediate ingredient has been expanded.
 *
 * <p>{@code baseMaterials} counts only items that must come from outside the recipe tree (mined,
 * gathered or already held), so the planner can turn a "craft 64 chests" objective into the logs it
 * actually has to collect.
 */
public final class RecipePlan {
    private final String itemId;
    private final int amount;
    private final String recipeId;
    private final Map<String, Integer> baseMaterials;
    private final List<Step> steps;
    private final List<String> missing;

    public RecipePlan(String itemId, int amount, String recipeId, Map<String, Integer> baseMaterials,
                      List<Step> steps, List<String> missing) {
        this.itemId = itemId;
        this.amount = amount;
        this.recipeId = recipeId;
        this.baseMaterials = new LinkedHashMap<>(baseMaterials);
        this.steps = new ArrayList<>(steps);
        this.missing = new ArrayList<>(missing);
    }

    /** One craft operation in dependency order. */
    public record Step(String itemId, String recipeId, int times, List<Ingredient> ingredients) {
        public String describe() {
            return times + " x " + itemId + " via " + recipeId;
        }
    }

    /** One ingredient of a step. */
    public record Ingredient(String itemId, int count) {
    }

    public String itemId() {
        return itemId;
    }

    public int amount() {
        return amount;
    }

    public String recipeId() {
        return recipeId;
    }

    /** Items that must be sourced from outside the recipe tree, keyed by item id. */
    public Map<String, Integer> baseMaterials() {
        return new LinkedHashMap<>(baseMaterials);
    }

    /** Craft operations in the order they must run. */
    public List<Step> steps() {
        return new ArrayList<>(steps);
    }

    /** Ingredients the planner could not resolve at all. */
    public List<String> missing() {
        return new ArrayList<>(missing);
    }

    public boolean feasible() {
        return missing.isEmpty() && !steps.isEmpty();
    }

    public String describe() {
        if (!feasible()) return "Cannot craft " + itemId + ": missing " + String.join(", ", missing);
        return "Craft " + amount + " x " + itemId + " from " + baseMaterials.size() + " base material(s)";
    }
}
