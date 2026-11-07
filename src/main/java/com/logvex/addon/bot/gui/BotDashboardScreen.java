package com.logvex.addon.bot.gui;

import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotEvent;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.bot.core.GoalManager;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;
import java.util.Locale;

import static com.logvex.addon.framework.Addon.mc;

/**
 * The bot's main screen: current state, the active task, the queue, the objectives and the recent
 * event log, with the controls to start, pause, resume and stop.
 *
 * <p>Everything shown is read live from the controller, so a paused or finished bot shows its real
 * state rather than a cached one.
 */
public class BotDashboardScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color AMBER = new Color(240, 200, 110);
    private static final Color GREY = new Color(170, 170, 170);

    private final BotModule module;

    private WTextBox objectiveBox;
    private WTable overview;
    private WTable queueTable;
    private WTable eventTable;

    public BotDashboardScreen(BotModule module) {
        this(GuiThemes.get(), module);
    }

    public BotDashboardScreen(GuiTheme theme, BotModule module) {
        super(theme, "Auto Bot");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        WButton start = controls.add(theme.button("Start")).expandX().widget();
        start.action = module::startBot;
        WButton pause = controls.add(theme.button("Pause")).expandX().widget();
        pause.action = module::pauseBot;
        WButton resume = controls.add(theme.button("Resume")).expandX().widget();
        resume.action = module::resumeBot;
        WButton stop = controls.add(theme.button("Stop")).expandX().widget();
        stop.action = module::stopBot;

        WHorizontalList controls2 = add(theme.horizontalList()).expandX().widget();
        WButton emergency = controls2.add(theme.button("Emergency Stop")).expandX().widget();
        emergency.action = () -> {
            module.emergencyStop();
            refresh();
        };
        WButton tasks = controls2.add(theme.button("Task Manager...")).expandX().widget();
        tasks.action = () -> mc.setScreen(new TaskManagerScreen(module));
        WButton memory = controls2.add(theme.button("Memory...")).expandX().widget();
        memory.action = () -> mc.setScreen(new MemoryScreen(module));
        WButton profiles = controls2.add(theme.button("Profiles...")).expandX().widget();
        profiles.action = () -> mc.setScreen(new ProfileScreen(module));

        WHorizontalList controls3 = add(theme.horizontalList()).expandX().widget();
        WButton clearQueue = controls3.add(theme.button("Clear Queue")).expandX().widget();
        clearQueue.action = () -> {
            module.controller().queue().clear();
            refresh();
        };
        WButton clearEvents = controls3.add(theme.button("Clear Events")).expandX().widget();
        clearEvents.action = () -> {
            module.controller().events().clear();
            refresh();
        };

        add(theme.horizontalSeparator()).expandX();

        WHorizontalList objectiveRow = add(theme.horizontalList()).expandX().widget();
        objectiveBox = objectiveRow.add(theme.textBox("", "e.g. gather 256 oak_log")).expandX().widget();
        WButton add = objectiveRow.add(theme.button("Add Objective")).widget();
        add.action = this::addObjective;

        add(theme.horizontalSeparator()).expandX();
        overview = add(theme.table()).expandX().widget();

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Queue")).expandX();
        queueTable = add(theme.table()).expandX().widget();

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Recent Events")).expandX();
        eventTable = add(theme.table()).expandX().widget();

        refresh();
    }

    private void addObjective() {
        if (objectiveBox == null) return;
        ObjectiveParser.Parsed parsed = ObjectiveParser.parse(objectiveBox.get());
        if (parsed == null) {
            module.error("Could not understand the objective. Try: gather 256 oak_log");
            return;
        }
        module.addGoal(parsed.description(), parsed.type(), parsed.itemId(), parsed.amount());
        objectiveBox.set("");
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 10 == 0) refresh();
    }

    private void refresh() {
        if (overview == null) return;
        overview.clear();
        overview.add(theme.label("State: " + module.controller().state())).expandCellX();
        overview.row();
        BotTask active = module.controller().activeTask();
        overview.add(theme.label("Active: " + (active == null ? "none" : active.describe()))).expandCellX();
        overview.row();
        if (active != null && active.required() > 0) {
            overview.add(theme.label(String.format(Locale.ROOT, "Progress: %d / %d (%.0f%%)",
                active.progress(), active.required(), active.fraction() * 100))).expandCellX();
            overview.row();
        }
        overview.add(theme.label("Queued tasks: " + module.controller().queue().size()
            + "   Objectives: " + module.controller().goals().pendingCount())).expandCellX();
        overview.row();
        GoalManager.Goal goal = module.controller().goals().active();
        overview.add(theme.label("Current objective: " + (goal == null ? "none" : goal.description()))).expandCellX();
        overview.row();
        overview.add(theme.label(String.format(Locale.ROOT, "Tick: %.0f us avg   Actions: %d/s",
            module.controller().performance().averageTickMicros(),
            module.controller().performance().actionsPerSecond()))).expandCellX();
        overview.row();
        overview.add(theme.label("Navigation: " + module.navigationBackend())).expandCellX();
        overview.row();
        var navTarget = module.navTarget();
        if (navTarget != null) {
            double distance = module.navDistance();
            overview.add(theme.label(String.format(Locale.ROOT, "Destination: %s (%.1f blocks away)",
                navTarget, distance))).expandCellX();
            overview.row();
        } else {
            overview.add(theme.label("Destination: none")).expandCellX();
            overview.row();
        }

        if (queueTable != null) {
            queueTable.clear();
            List<BotTask> tasks = module.controller().queue().tasks();
            if (tasks.isEmpty()) {
                queueTable.add(theme.label("The queue is empty.")).expandCellX();
                queueTable.row();
            } else {
                int shown = 0;
                for (BotTask task : tasks) {
                    if (shown++ >= 12) break;
                    queueTable.add(theme.label("#" + task.id() + "  " + task.describe())).expandCellX();
                    queueTable.add(theme.label(task.state().toString()).color(stateColor(task)));
                    queueTable.row();
                }
                if (tasks.size() > 12) {
                    queueTable.add(theme.label("... and " + (tasks.size() - 12) + " more")).expandCellX();
                    queueTable.row();
                }
            }
        }

        if (eventTable != null) {
            eventTable.clear();
            List<BotEvent> events = module.controller().events();
            if (events.isEmpty()) {
                eventTable.add(theme.label("No events yet.")).expandCellX();
                eventTable.row();
            } else {
                int shown = 0;
                for (BotEvent event : events) {
                    if (shown++ >= 10) break;
                    eventTable.add(theme.label(Messages.clock(event.at()))).widget();
                    eventTable.add(theme.label(event.message()).color(eventColor(event))).expandCellX();
                    eventTable.row();
                }
            }
        }
    }

    private Color stateColor(BotTask task) {
        return switch (task.state()) {
            case DONE -> GREEN;
            case FAILED -> RED;
            case RUNNING -> AMBER;
            default -> GREY;
        };
    }

    private Color eventColor(BotEvent event) {
        return switch (event.level()) {
            case SUCCESS -> GREEN;
            case ERROR -> RED;
            case WARNING -> AMBER;
            case INFO -> GREY;
        };
    }
}
