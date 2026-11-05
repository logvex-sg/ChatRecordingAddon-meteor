package com.logvex.addon.bot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a user objective into an ordered list of tasks.
 *
 * <p>The planner is deterministic and offline. It recognises the objective's type, and for a craft
 * objective it expands the recipe tree with {@link RecipePlanner} so the gather tasks for the base
 * materials come before the craft steps. If the objective cannot be understood the planner returns
 * an empty list and the caller reports why, rather than inventing a task that does nothing.
 */
public final class TaskPlanner {
    private TaskPlanner() {
    }

    /** Recognised objective types, mapped from the user's word. */
    private static final Map<String, TaskType> TYPE_ALIASES = new LinkedHashMap<>();

    static {
        alias("gather", TaskType.GATHERING);
        alias("collect", TaskType.GATHERING);
        alias("get", TaskType.GATHERING);
        alias("mine", TaskType.MINING);
        alias("dig", TaskType.MINING);
        alias("woodcut", TaskType.WOODCUTTING);
        alias("chop", TaskType.WOODCUTTING);
        alias("wood", TaskType.WOODCUTTING);
        alias("log", TaskType.WOODCUTTING);
        alias("farm", TaskType.FARMING);
        alias("harvest", TaskType.FARMING);
        alias("craft", TaskType.CRAFTING);
        alias("make", TaskType.CRAFTING);
        alias("smelt", TaskType.SMELTING);
        alias("cook", TaskType.SMELTING);
        alias("build", TaskType.BUILDING);
        alias("travel", TaskType.NAVIGATION);
        alias("goto", TaskType.NAVIGATION);
        alias("go", TaskType.NAVIGATION);
        alias("return", TaskType.NAVIGATION);
        alias("explore", TaskType.EXPLORATION);
        alias("store", TaskType.STORAGE);
        alias("organize", TaskType.STORAGE);
        alias("fish", TaskType.FISHING);
        alias("transport", TaskType.TRANSPORT);
        alias("maintain", TaskType.MAINTENANCE);
        alias("inventory", TaskType.INVENTORY);
    }

    private static void alias(String word, TaskType type) {
        TYPE_ALIASES.put(word, type);
    }

    /** The task type for an objective word, or {@code null}. */
    public static TaskType typeFor(String word) {
        if (word == null) return null;
        return TYPE_ALIASES.get(word.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Plans the tasks for {@code goal}. Returns an empty list when the objective cannot be planned.
     */
    public static List<BotTask> plan(GoalManager.Goal goal, TaskContext context) {
        List<BotTask> tasks = new ArrayList<>();
        if (goal == null) return tasks;

        TaskType type = typeFor(goal.type());
        if (type == null) {
            // Fall back to the item-based reading: a bare "256 oak_log" is a gather objective.
            type = goal.itemId().isEmpty() ? null : TaskType.GATHERING;
        }
        if (type == null) return tasks;

        if (type == TaskType.CRAFTING) {
            planCraft(goal, context, tasks);
            return tasks;
        }
        if (type == TaskType.BUILDING) {
            BotTask build = new BotTask(TaskType.BUILDING, goal.description())
                .itemId(goal.itemId())
                .required(goal.amount());
            tasks.add(build);
            return tasks;
        }
        if (type == TaskType.NAVIGATION) {
            BotTask nav = new BotTask(TaskType.NAVIGATION, goal.description())
                .itemId(goal.itemId())
                .required(goal.amount());
            tasks.add(nav);
            return tasks;
        }
        if (type == TaskType.STORAGE || type == TaskType.INVENTORY || type == TaskType.MAINTENANCE) {
            tasks.add(new BotTask(type, goal.description()).required(goal.amount()));
            return tasks;
        }

        // Gathering-family: one task that collects the item.
        if (goal.itemId().isEmpty()) return tasks;
        tasks.add(new BotTask(type, goal.description())
            .itemId(goal.itemId())
            .required(goal.amount()));
        return tasks;
    }

    /**
     * Plans a craft objective: gather the base materials first, then run the craft steps in
     * dependency order.
     */
    private static void planCraft(GoalManager.Goal goal, TaskContext context, List<BotTask> tasks) {
        if (goal.itemId().isEmpty() || context == null || context.recipes() == null) return;

        Map<String, Integer> held = context.inventory() == null
            ? Map.of() : context.inventory().snapshot().totals();
        RecipePlanner planner = new RecipePlanner(context.recipes(), context.items());
        RecipePlan plan = planner.plan(goal.itemId(), goal.amount(), held);

        if (!plan.feasible()) {
            return; // The caller reports "not plannable".
        }

        // Gather every base material the player does not already hold enough of.
        for (Map.Entry<String, Integer> entry : plan.baseMaterials().entrySet()) {
            int have = held.getOrDefault(entry.getKey(), 0);
            int missing = entry.getValue() - have;
            if (missing <= 0) continue;
            BotTask gather = new BotTask(gatherTypeFor(entry.getKey()), "Gather " + entry.getKey())
                .itemId(entry.getKey())
                .required(missing);
            tasks.add(gather);
        }

        // Then the craft steps, in the order the planner produced them.
        for (RecipePlan.Step step : plan.steps()) {
            BotTask craft = new BotTask(TaskType.CRAFTING, "Craft " + step.itemId())
                .itemId(step.itemId())
                .required(step.times() * Math.max(1, outputCount(context, step)))
                .setParameter("recipe", step.recipeId());
            tasks.add(craft);
        }
    }

    private static int outputCount(TaskContext context, RecipePlan.Step step) {
        if (context.recipes() == null) return 1;
        for (RecipeSource.Recipe recipe : context.recipes().recipesFor(step.itemId())) {
            if (recipe.id().equals(step.recipeId())) return Math.max(1, recipe.outputCount());
        }
        return 1;
    }

    /** A gathering task type suited to the item: logs use woodcutting, otherwise gathering. */
    private static TaskType gatherTypeFor(String itemId) {
        String path = itemId;
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        if (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_wood")
            || path.endsWith("_hyphae")) {
            return TaskType.WOODCUTTING;
        }
        if (path.endsWith("_ore") || path.equals("ancient_debris") || path.startsWith("deepslate")) {
            return TaskType.MINING;
        }
        return TaskType.GATHERING;
    }
}
