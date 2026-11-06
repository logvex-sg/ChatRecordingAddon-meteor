package com.logvex.addon.schematicdropper.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.schematicdropper.SchematicBuildController;
import com.logvex.addon.schematicdropper.SchematicDropperModule;
import com.logvex.addon.schematicdropper.core.BuildStatistics;
import com.logvex.addon.schematicdropper.core.BuildTask;
import com.logvex.addon.schematicdropper.core.Checkpoint;
import com.logvex.addon.schematicdropper.core.FailureLog;
import com.logvex.addon.schematicdropper.core.PlacementSnapshot;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;
import java.util.Locale;

import static com.logvex.addon.framework.MeteorClient.mc;

/**
 * Live view of a schematic build: progress, the active queue, recent problems and the checkpoint
 * list. Every value comes from the engine snapshot or the controller, so nothing is invented.
 */
public class BuildScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color AMBER = new Color(240, 200, 110);
    private static final Color GREY = new Color(170, 170, 170);

    private final SchematicDropperModule module;
    private final SchematicBuildController build;

    private WTable table;
    private int section;

    public BuildScreen(SchematicDropperModule module) {
        this(GuiThemes.get(), module);
    }

    public BuildScreen(GuiTheme theme, SchematicDropperModule module) {
        super(theme, "Schematic Builder");
        this.module = module;
        this.build = module.buildController();
    }

    @Override
    public void initWidgets() {
        add(theme.label("Schematic Builder")).expandX();
        add(theme.horizontalSeparator()).expandX();

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        WButton load = controls.add(theme.button("Load...")).widget();
        load.action = () -> {
            module.loadSchematicFile();
            refresh();
        };
        WButton start = controls.add(theme.button("Start")).widget();
        start.action = () -> {
            module.startBuild();
            refresh();
        };
        WButton pause = controls.add(theme.button("Pause")).widget();
        pause.action = module::pauseBuild;
        WButton resume = controls.add(theme.button("Resume")).widget();
        resume.action = module::resumeBuild;
        WButton stop = controls.add(theme.button("Stop")).widget();
        stop.action = module::stopBuild;

        WHorizontalList controls2 = add(theme.horizontalList()).expandX().widget();
        WButton retry = controls2.add(theme.button("Retry Failed")).widget();
        retry.action = () -> {
            module.retryFailedBlocks();
            refresh();
        };
        WButton save = controls2.add(theme.button("Save Checkpoint")).widget();
        save.action = () -> {
            build.saveCheckpointNow();
            refresh();
        };
        WButton tab = controls2.add(theme.button("")).widget();
        tab.action = () -> {
            section = (section + 1) % 3;
            refresh();
        };
        // The button label reflects the section shown after the click.
        tab.set("View: " + sectionName((section + 1) % 3));

        add(theme.horizontalSeparator()).expandX();
        table = add(theme.table()).expandX().widget();
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 10 == 0) refresh();
    }

    private static String sectionName(int section) {
        return switch (section) {
            case 0 -> "Progress";
            case 1 -> "Problems";
            default -> "History";
        };
    }

    private void refresh() {
        if (table == null) return;
        table.clear();

        PlacementSnapshot snap = build.snapshot();
        table.add(theme.label("Schematic: "
            + (build.hasSchematic() ? build.schematic().name() : "none loaded"))).expandCellX();
        table.row();
        table.add(theme.label("Origin: " + build.origin()
            + "   State: " + snap.state()
            + (build.isLoading() ? "   (loading...)" : ""))).expandCellX();
        table.row();

        switch (section) {
            case 0 -> progressSection(snap);
            case 1 -> problemsSection();
            default -> historySection();
        }
    }

    private void progressSection(PlacementSnapshot snap) {
        table.add(theme.label(String.format(Locale.ROOT,
            "Progress: %.1f%%   placed %s   failed %s   skipped %s   remaining %s",
            snap.percent(), Messages.number(snap.completed()), Messages.number(snap.failed()),
            Messages.number(snap.skipped()), Messages.number(snap.remaining())))).expandCellX();
        table.row();
        table.add(theme.label("Already correct: " + Messages.number(snap.alreadyCorrect())
            + "   Conflicts: " + Messages.number(snap.conflicts())
            + "   Retries: " + Messages.number(snap.retries())
            + "   Elapsed: " + Messages.duration(snap.elapsedMs()))).expandCellX();
        table.row();

        if (snap.currentPos() != null) {
            table.add(theme.label("Placing " + Messages.prettyId(
                snap.currentTarget() == null ? "?" : snap.currentTarget().id())
                + " at " + snap.currentPos())).expandCellX();
            table.row();
        }

        List<BuildTask> tasks = build.queue().tasks();
        if (!tasks.isEmpty()) {
            table.add(theme.horizontalSeparator()).expandX();
            table.row();
            table.add(theme.label("Queue (" + tasks.size() + ")")).expandCellX();
            table.row();
            for (BuildTask task : tasks) {
                table.add(theme.label(Messages.truncate(task.describe(), 48))
                    .color(task.status() == BuildTask.Status.RUNNING ? GREEN : GREY)).expandCellX();
                table.row();
            }
        }
    }

    private void problemsSection() {
        FailureLog failures = build.failures();
        if (failures.isEmpty()) {
            table.add(theme.label("No problems recorded.").color(GREEN)).expandCellX();
            table.row();
            return;
        }
        table.add(theme.label(failures.size() + " problems (newest first)")).expandCellX();
        table.row();
        table.add(theme.horizontalSeparator()).expandX();
        table.row();
        for (FailureLog.Entry entry : failures.entries()) {
            Color color = entry.recoverable() ? AMBER : RED;
            table.add(theme.label(Messages.clock(entry.at())).color(GREY));
            table.add(theme.label(Messages.truncate(entry.describe(), 56)).color(color)).expandCellX();
            table.row();
        }
    }

    private void historySection() {
        BuildStatistics statistics = build.statistics();
        table.add(theme.label("Sessions: " + Messages.number(statistics.totalSessions())
            + "   Placed: " + Messages.number(statistics.totalPlaced())
            + "   Failed: " + Messages.number(statistics.totalFailed()))).expandCellX();
        table.row();
        table.add(theme.label("Active time: " + Messages.duration(statistics.totalActiveMs())
            + "   Rate: " + Messages.decimal(statistics.placementRate(), 1) + " blocks/min")).expandCellX();
        table.row();

        List<BuildStatistics.Session> history = statistics.history();
        if (!history.isEmpty()) {
            table.add(theme.horizontalSeparator()).expandX();
            table.row();
            table.add(theme.label("Recent builds")).expandCellX();
            table.row();
            for (BuildStatistics.Session session : history) {
                table.add(theme.label(Messages.truncate(session.summary(), 64))
                    .color(session.completed() ? GREEN : AMBER)).expandCellX();
                table.row();
            }
        }

        List<Checkpoint> checkpoints = build.storedCheckpoints();
        if (!checkpoints.isEmpty()) {
            table.add(theme.horizontalSeparator()).expandX();
            table.row();
            table.add(theme.label("Saved checkpoints")).expandCellX();
            table.row();
            for (Checkpoint checkpoint : checkpoints) {
                table.add(theme.label(checkpoint.schematicName()
                    + " - " + checkpoint.completed().size() + "/" + checkpoint.totalBlocks()
                    + " at " + Messages.clock(checkpoint.updatedAt()))).expandCellX();
                table.row();
            }
        }
    }
}
