package com.logvex.addon.bot.mc;

import com.logvex.addon.bot.core.RecipeSource;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * {@link RecipeSource} backed by the live recipe manager.
 *
 * <p>Building the full recipe table once and indexing it by output item keeps the planner's
 * {@code recipesFor} lookup cheap, instead of scanning every recipe each time the bot plans a craft.
 * The table is rebuilt whenever {@link #rebuild()} is called (a resource reload or a world join).
 */
public final class MinecraftRecipeSource implements RecipeSource {
    private final Map<String, List<Recipe>> byOutput = new LinkedHashMap<>();

    /** Rebuilds the index from the current recipe manager. Safe to call on any tick. */
    public void rebuild() {
        byOutput.clear();
        if (mc.world == null) return;
        var manager = mc.world.getRecipeManager();
        if (manager == null) return;

        for (RecipeEntry<?> entry : manager.values()) {
            net.minecraft.recipe.Recipe<?> recipe = entry.value();
            ItemStack result;
            try {
                result = recipe.getResult(mc.world.getRegistryManager());
            } catch (RuntimeException e) {
                continue;
            }
            if (result == null || result.isEmpty()) continue;
            String outputId = Registries.ITEM.getId(result.getItem()).toString();

            List<RecipeSource.Ingredient> ingredients = new ArrayList<>();
            for (net.minecraft.recipe.Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                ingredients.add(toIngredient(ingredient));
            }
            RecipeSource.Recipe mapped =
                new RecipeSource.Recipe(entry.id().toString(), outputId, result.getCount(), ingredients);
            byOutput.computeIfAbsent(outputId, k -> new ArrayList<>()).add(mapped);
        }
    }

    @Override
    public List<Recipe> recipesFor(String itemId) {
        if (itemId == null) return List.of();
        List<Recipe> recipes = byOutput.get(itemId);
        return recipes == null ? List.of() : List.copyOf(recipes);
    }

    public boolean isEmpty() {
        return byOutput.isEmpty();
    }

    public int size() {
        return byOutput.size();
    }

    private static RecipeSource.Ingredient toIngredient(net.minecraft.recipe.Ingredient ingredient) {
        List<String> options = new ArrayList<>();
        for (ItemStack stack : ingredient.getMatchingStacks()) {
            if (stack == null || stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (!options.contains(id)) options.add(id);
        }
        String label = options.isEmpty() ? "unknown" : options.get(0);
        return new RecipeSource.Ingredient(label, options, 1);
    }

    /** Resolves an id, returning null when it is not a valid identifier. */
    static Identifier id(String value) {
        return value == null ? null : Identifier.tryParse(value);
    }
}
