package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.RecipePlan;
import com.logvex.addon.bot.core.RecipePlanner;
import com.logvex.addon.bot.core.RecipeSource;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Runs a craft once its ingredients are present.
 *
 * <p>The executor never starts a craft it cannot verify. It re-reads the recipe, checks the
 * inventory for every ingredient, and only then asks the action port to craft. If an ingredient is
 * missing it reports {@link TaskOutcome#RETRY} so the controller can plan a gather, rather than
 * running a craft that would silently produce nothing.
 */
public final class CraftingExecutor implements TaskExecutor {
    @Override
    public TaskType type() {
        return TaskType.CRAFTING;
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        if (context.recipes() == null) return TaskOutcome.FAILED;
        String recipeId = task.parameter("recipe", "");
        RecipeSource.Recipe recipe = find(context, task.itemId(), recipeId);
        if (recipe == null) return TaskOutcome.FAILED;

        int have = context.inventory().snapshot().countOf(task.itemId());
        if (have >= task.required()) {
            task.setProgress(task.required());
            return TaskOutcome.DONE;
        }

        for (RecipeSource.Ingredient ingredient : recipe.ingredients()) {
            if (!hasIngredient(context, ingredient, recipe)) return TaskOutcome.RETRY;
        }

        if (context.actions().craft(recipe.id(), 1)) {
            task.addProgress(Math.max(1, recipe.outputCount()));
        }
        return TaskOutcome.CONTINUE;
    }

    @Override
    public String status(BotTask task) {
        return "Crafting " + task.itemId();
    }

    private static RecipeSource.Recipe find(TaskContext context, String itemId, String recipeId) {
        for (RecipeSource.Recipe recipe : context.recipes().recipesFor(itemId)) {
            if (recipeId.isEmpty() || recipe.id().equals(recipeId)) return recipe;
        }
        return recipeId.isEmpty() ? context.recipes().bestRecipeFor(itemId) : null;
    }

    private static boolean hasIngredient(TaskContext context, RecipeSource.Ingredient ingredient,
                                         RecipeSource.Recipe recipe) {
        if (ingredient.options() == null || ingredient.options().isEmpty()) return false;
        for (String option : ingredient.options()) {
            if (context.inventory().snapshot().countOf(option) >= ingredient.count()) return true;
        }
        return false;
    }
}
