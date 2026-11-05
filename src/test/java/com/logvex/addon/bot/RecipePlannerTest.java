package com.logvex.addon.bot;

import com.logvex.addon.bot.core.RecipePlan;
import com.logvex.addon.bot.core.RecipePlanner;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recipe planner must expand a craft objective into the base materials that actually have to be
 * gathered, and must not claim a plan is feasible when an ingredient cannot be resolved.
 */
class RecipePlannerTest {
    @Test
    void aSimpleRecipeExpandsToOneBaseMaterial() {
        BotFakes.FakeRecipes recipes = new BotFakes.FakeRecipes();
        recipes.add("minecraft:oak_planks", 4,
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("minecraft:oak_log",
                java.util.List.of("minecraft:oak_log"), 1));

        RecipePlanner planner = new RecipePlanner(recipes, new BotFakes.FakeKnowledge());
        RecipePlan plan = planner.plan("minecraft:oak_planks", 4, Map.of());

        assertTrue(plan.feasible());
        assertEquals(1, plan.baseMaterials().getOrDefault("minecraft:oak_log", 0));
    }

    @Test
    void aNestedRecipeExpandsToItsRootMaterials() {
        BotFakes.FakeRecipes recipes = new BotFakes.FakeRecipes();
        recipes.add("minecraft:oak_planks", 4,
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("minecraft:oak_log",
                java.util.List.of("minecraft:oak_log"), 1));
        recipes.add("minecraft:crafting_table", 1,
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("minecraft:oak_planks",
                java.util.List.of("minecraft:oak_planks"), 4));

        RecipePlanner planner = new RecipePlanner(recipes, new BotFakes.FakeKnowledge());
        RecipePlan plan = planner.plan("minecraft:crafting_table", 1, Map.of());

        assertTrue(plan.feasible());
        assertEquals(1, plan.baseMaterials().getOrDefault("minecraft:oak_log", 0));
    }

    @Test
    void anUnresolvableIngredientMakesThePlanInfeasible() {
        BotFakes.FakeRecipes recipes = new BotFakes.FakeRecipes();
        recipes.add("minecraft:stick", 4,
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("unknown", java.util.List.of(), 1));

        RecipePlanner planner = new RecipePlanner(recipes, new BotFakes.FakeKnowledge());
        RecipePlan plan = planner.plan("minecraft:stick", 4, Map.of());

        assertFalse(plan.feasible());
        assertFalse(plan.missing().isEmpty());
    }

    @Test
    void anItemWithNoRecipeIsInfeasible() {
        BotFakes.FakeRecipes recipes = new BotFakes.FakeRecipes();
        RecipePlanner planner = new RecipePlanner(recipes, new BotFakes.FakeKnowledge());
        RecipePlan plan = planner.plan("minecraft:diamond_block", 1, Map.of());
        assertFalse(plan.feasible());
    }

    @Test
    void aRecipeWithSeveralOptionsPicksAStableFirstOption() {
        BotFakes.FakeRecipes recipes = new BotFakes.FakeRecipes();
        recipes.add("minecraft:stick", 4,
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("minecraft:oak_planks",
                java.util.List.of("minecraft:oak_planks", "minecraft:birch_planks"), 2));

        RecipePlanner planner = new RecipePlanner(recipes, new BotFakes.FakeKnowledge());
        RecipePlan plan = planner.plan("minecraft:stick", 4, Map.of());

        assertTrue(plan.feasible());
        assertEquals(2, plan.baseMaterials().getOrDefault("minecraft:oak_planks", 0));
    }

    @Test
    void bestRecipePrefersTheFewestIngredients() {
        BotFakes.FakeRecipes recipes = new BotFakes.FakeRecipes();
        recipes.add("minecraft:planks", 4,
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("a", java.util.List.of("a"), 1),
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("b", java.util.List.of("b"), 1));
        recipes.add("minecraft:planks", 4,
            new com.logvex.addon.bot.core.RecipeSource.Ingredient("a", java.util.List.of("a"), 1));

        var best = recipes.bestRecipeFor("minecraft:planks");
        assertEquals(1, best.ingredients().size());
    }
}
