package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands a "craft N of X" objective into a dependency-ordered list of craft steps plus the base
 * materials that must be gathered.
 *
 * <p>The planner is deterministic and offline. It walks the recipe tree depth-first, choosing the
 * recipe with the fewest ingredients at each level, and stops a branch when it reaches an item the
 * player already holds enough of or an item with no recipe (a base material). A visited set breaks
 * recipe cycles, and a depth cap stops pathological recursion, so an odd recipe graph cannot hang
 * the client thread.
 */
public final class RecipePlanner {
    /** Maximum recipe-tree depth. Deeper than any real Minecraft chain, but finite. */
    public static final int MAX_DEPTH = 16;

    private final RecipeSource recipes;
    private final ItemKnowledge knowledge;

    public RecipePlanner(RecipeSource recipes, ItemKnowledge knowledge) {
        this.recipes = recipes;
        this.knowledge = knowledge;
    }

    /**
     * Plans {@code amount} of {@code itemId}.
     *
     * @param available counts of items the player already holds, so the planner can skip branches
     *                  it does not need to source
     */
    public RecipePlan plan(String itemId, int amount, Map<String, Integer> available) {
        Map<String, Integer> held = new LinkedHashMap<>(available == null ? Map.of() : available);
        Map<String, Integer> base = new LinkedHashMap<>();
        List<RecipePlan.Step> steps = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        expand(itemId, amount, held, base, steps, missing, 0, new LinkedHashSet<>());

        // Steps were collected leaf-first (deepest first). Executing them in that order means every
        // intermediate is ready before the step that consumes it.
        return new RecipePlan(itemId, amount, steps.isEmpty() ? "" : steps.get(steps.size() - 1).recipeId(),
            base, steps, missing);
    }

    /** How much of {@code itemId} can be crafted from what the player already holds. */
    public int craftableAmount(String itemId, Map<String, Integer> available) {
        RecipePlan plan = plan(itemId, 1, available);
        if (!plan.feasible()) return 0;
        // One unit needs one unit of each base material; the bottleneck is the scarcest ratio.
        int craftable = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : plan.baseMaterials().entrySet()) {
            int have = available.getOrDefault(entry.getKey(), 0);
            int needPerUnit = Math.max(1, entry.getValue());
            craftable = Math.min(craftable, have / needPerUnit);
        }
        return craftable == Integer.MAX_VALUE ? 0 : craftable;
    }

    private void expand(String itemId, int needed, Map<String, Integer> held, Map<String, Integer> base,
                        List<RecipePlan.Step> steps, List<String> missing, int depth,
                        Set<String> visiting) {
        if (needed <= 0) return;

        // Consume from what is already held first.
        int have = held.getOrDefault(itemId, 0);
        if (have > 0) {
            int used = Math.min(have, needed);
            held.put(itemId, have - used);
            needed -= used;
            if (needed <= 0) return;
        }

        RecipeSource.Recipe recipe = recipes.bestRecipeFor(itemId);
        if (recipe == null || depth >= MAX_DEPTH || visiting.contains(itemId)) {
            // No recipe: this is a base material the bot must gather.
            base.merge(itemId, needed, Integer::sum);
            return;
        }

        int outputCount = Math.max(1, recipe.outputCount());
        int times = (int) Math.ceil((double) needed / outputCount);
        int produced = times * outputCount;

        visiting.add(itemId);
        List<RecipePlan.Ingredient> ingredients = new ArrayList<>();
        for (RecipeSource.Ingredient ingredient : recipe.ingredients()) {
            int total = ingredient.count() * times;
            String resolved = resolveIngredient(ingredient);
            if (resolved == null) {
                if (!missing.contains(ingredient.id())) missing.add(ingredient.id());
                continue;
            }
            ingredients.add(new RecipePlan.Ingredient(resolved, total));
            // An ingredient already satisfied by another option of the same ingredient is not
            // expanded again, so a tag with a held member does not force a gather.
            if (satisfiedByHeld(ingredient, total, held)) continue;
            expand(resolved, total, held, base, steps, missing, depth + 1, visiting);
        }
        visiting.remove(itemId);

        steps.add(new RecipePlan.Step(itemId, recipe.id(), times, List.copyOf(ingredients)));

        // The surplus output can satisfy later demand for the same item.
        if (produced > needed) held.merge(itemId, produced - needed, Integer::sum);
    }

    /**
     * Resolves an ingredient to a concrete item id. An ingredient with several options resolves to
     * its first option, a deterministic and always-valid choice for planning.
     */
    private String resolveIngredient(RecipeSource.Ingredient ingredient) {
        if (ingredient.options() == null || ingredient.options().isEmpty()) return null;
        return ingredient.options().get(0);
    }

    /** True when the player already holds enough of any option of {@code ingredient}. */
    private static boolean satisfiedByHeld(RecipeSource.Ingredient ingredient, int total,
                                           Map<String, Integer> held) {
        if (ingredient.options() == null) return false;
        for (String option : ingredient.options()) {
            if (held.getOrDefault(option, 0) >= total) return true;
        }
        return false;
    }
}
