package com.logvex.addon.bot.exec;

import com.logvex.addon.bot.core.BuildPort;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskContext;
import com.logvex.addon.bot.core.TaskExecutor;
import com.logvex.addon.bot.core.TaskOutcome;
import com.logvex.addon.bot.core.TaskType;

/**
 * Drives the existing schematic build subsystem from a task.
 *
 * <p>All placement, verification and checkpoint logic lives in the schematic build controller; this
 * executor only starts it and watches its progress. That keeps one implementation of the hard part
 * and lets the bot schedule a build alongside its other work.
 */
public final class BuildExecutor implements TaskExecutor {
    @Override
    public TaskType type() {
        return TaskType.BUILDING;
    }

    @Override
    public TaskOutcome step(BotTask task, TaskContext context) {
        BuildPort build = context.build();
        if (build == null || !build.hasSchematic()) return TaskOutcome.FAILED;

        if (!build.running()) {
            if (!build.startBuild()) {
                String error = build.lastError();
                return error.isEmpty() ? TaskOutcome.RETRY : TaskOutcome.FAILED;
            }
        }
        task.setProgress((int) Math.round(build.progress() * Math.max(1, task.required())));
        return build.running() ? TaskOutcome.CONTINUE : TaskOutcome.DONE;
    }

    @Override
    public void end(BotTask task, TaskContext context) {
        if (context.build() != null) context.build().stopBuild();
    }

    @Override
    public String status(BotTask task) {
        return "Building";
    }
}
