package com.logvex.addon.bot.gui;

import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.TaskHistory;
import com.logvex.addon.common.Messages;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A searchable view of the task queue and the finished-task history.
 *
 * <p>It never mutates a task while the bot is running it; the controls only cancel or reorder
 * pending work, and the table is rebuilt from the live queue each refresh.
 */
public class TaskManagerScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color AMBER = new Color(240, 200, 110);
    private static final Color GREY = new Color(170, 170, 170);

    private final BotModule module;

    private WTextBox search;
    private WTable queueTable;
    private WTable historyTable;

    private String filter = "";

    public TaskManagerScreen(BotModule module) {
        this(GuiThemes.get(), module);
    }

    public TaskManagerScreen(GuiTheme theme, BotModule module) {
        super(theme, "Task Manager");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        WButton pause = controls.add(theme.button("Pause")).expandX().widget();
        pause.action = module::pauseBot;
        WButton resume = controls.add(theme.button("Resume")).expandX().widget();
        resume.action = module::resumeBot;
        WButton stop = controls.add(theme.button("Stop")).expandX().widget();
        stop.action = module::stopBot;
        WButton clear = controls.add(theme.button("Clear Finished")).expandX().widget();
        clear.action = () -> {
            module.controller().queue().pruneFinished();
            module.controller().history().clear();
            refresh();
        };

        search = add(theme.textBox("", "Search tasks...")).expandX().widget();
        search.action = () -> {
            filter = search.get().trim().toLowerCase(Locale.ROOT);
            refresh();
        };

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Queue")).expandX();
        queueTable = add(theme.table()).expandX().widget();

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("History")).expandX();
        historyTable = add(theme.table()).expandX().widget();

        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 10 == 0) refresh();
    }

    private void refresh() {
        if (queueTable == null) return;
        queueTable.clear();

        List<BotTask> tasks = new ArrayList<>(module.controller().queue().tasks());
        if (!filter.isEmpty()) {
            tasks.removeIf(task -> !task.describe().toLowerCase(Locale.ROOT).contains(filter)
                && !task.type().name().toLowerCase(Locale.ROOT).contains(filter));
        }

        if (tasks.isEmpty()) {
            queueTable.add(theme.label(module.controller().queue().isEmpty()
                ? "The queue is empty. Add an objective on the dashboard."
                : "No queued tasks match the search.")).expandCellX();
            queueTable.row();
        } else {
            queueTable.add(theme.label("Task")).expandCellX();
            queueTable.add(theme.label("Type"));
            queueTable.add(theme.label("State"));
            queueTable.add(theme.label("Progress"));
            queueTable.add(theme.label(""));
            queueTable.row();

            for (BotTask task : tasks) {
                queueTable.add(theme.label("#" + task.id() + " " + task.describe())).expandCellX();
                queueTable.add(theme.label(task.type().toString()));
                queueTable.add(theme.label(task.state().toString()).color(stateColor(task)));
                queueTable.add(theme.label(task.required() > 0
                    ? task.progress() + "/" + task.required() : "-"));
                WButton cancel = queueTable.add(theme.button("Cancel")).widget();
                cancel.action = () -> {
                    module.controller().queue().remove(task.id());
                    refresh();
                };
                queueTable.row();
            }
        }

        if (historyTable != null) {
            historyTable.clear();
            List<TaskHistory.Entry> history = module.controller().history().entries();
            if (history.isEmpty()) {
                historyTable.add(theme.label("No tasks have finished yet.")).expandCellX();
                historyTable.row();
            } else {
                int shown = 0;
                for (TaskHistory.Entry entry : history) {
                    if (shown++ >= 20) break;
                    historyTable.add(theme.label(Messages.clock(entry.finishedAt()))).widget();
                    historyTable.add(theme.label(entry.description())).expandCellX();
                    historyTable.add(theme.label(entry.outcome().toString())
                        .color(entry.succeeded() ? GREEN : RED));
                    if (!entry.reason().isBlank()) {
                        historyTable.add(theme.label(Messages.truncate(entry.reason(), 40)).color(AMBER));
                    }
                    historyTable.row();
                }
            }
        }
    }

    private Color stateColor(BotTask task) {
        return switch (task.state()) {
            case DONE -> GREEN;
            case FAILED, CANCELLED -> RED;
            case RUNNING -> AMBER;
            default -> GREY;
        };
    }
}
