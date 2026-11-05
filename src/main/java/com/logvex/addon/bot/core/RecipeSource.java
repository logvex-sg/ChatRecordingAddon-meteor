package com.logvex.addon.bot.core;

import java.util.List;

/**
 * Supplies recipe data. The Minecraft layer implements this from the live recipe manager; tests use
 * a fixed table. A recipe is identified by id and consumes a list of ingredients.
 */
public interface RecipeSource {
    /**
     * One ingredient: the acceptable item ids and how many are needed. An ingredient with more than
     * one option is satisfied by any of them (Minecraft exposes these as a tag).
     */
    record Ingredient(String id, List<String> options, int count) {
        public boolean isEmpty() {
            return options == null || options.isEmpty();
        }
    }

    /** One recipe. */
    record Recipe(String id, String outputId, int outputCount, List<Ingredient> ingredients) {
    }

    /** All recipes that produce {@code itemId}, or an empty list when there is none. */
    List<Recipe> recipesFor(String itemId);

    /** The best recipe for {@code itemId}: the one with the fewest ingredients, or {@code null}. */
    default Recipe bestRecipeFor(String itemId) {
        Recipe best = null;
        for (Recipe recipe : recipesFor(itemId)) {
            if (best == null || recipe.ingredients().size() < best.ingredients().size()) best = recipe;
        }
        return best;
    }
}
