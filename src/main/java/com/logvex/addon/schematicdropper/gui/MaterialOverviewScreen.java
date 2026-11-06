package com.logvex.addon.schematicdropper.gui;

import com.logvex.addon.schematicdropper.SchematicDropperModule;
import com.logvex.addon.schematicdropper.core.MaterialProgress;
import com.logvex.addon.schematicdropper.core.ProgressSnapshot;
import com.logvex.addon.schematicdropper.core.ProgressTracker;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.logvex.addon.framework.MeteorClient.mc;

/**
 * Material overview: one row per material with live, confirmed progress values.
 *
 * <p>Uses only standard Meteor window widgets so it opens through the normal GUI stack and can be
 * opened from the module's ClickGUI screen.
 */
public class MaterialOverviewScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color AMBER = new Color(240, 200, 110);
    private static final Color GREY = new Color(170, 170, 170);

    private final SchematicDropperModule module;
    private final ProgressTracker tracker;

    private WTextBox search;
    private WTable table;
    private WButton sortButton;

    private String filter = "";
    private Sort sort = Sort.STATUS;

    public MaterialOverviewScreen(SchematicDropperModule module, ProgressTracker tracker) {
        this(GuiThemes.get(), module, tracker);
    }

    public MaterialOverviewScreen(GuiTheme theme, SchematicDropperModule module, ProgressTracker tracker) {
        super(theme, "Material Overview");
        this.module = module;
        this.tracker = tracker;
    }

    @Override
    public void initWidgets() {
        add(theme.label("Materials")).expandX();
        add(theme.horizontalSeparator()).expandX();

        search = add(theme.textBox("", "Search materials...")).minWidth(400).expandX().widget();
        search.action = () -> {
            filter = search.get().trim().toLowerCase(Locale.ROOT);
            refresh();
        };

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();

        sortButton = controls.add(theme.button("Sort: " + sort)).widget();
        sortButton.action = () -> {
            sort = sort.next();
            sortButton.set("Sort: " + sort);
            refresh();
        };

        WButton refresh = controls.add(theme.button("Refresh Inventory")).widget();
        refresh.action = () -> {
            module.rescanInventory();
            refresh();
        };

        WButton reset = controls.add(theme.button("Reset Progress")).widget();
        reset.action = () -> {
            module.resetProgress();
            refresh();
        };

        add(theme.horizontalSeparator()).expandX();

        table = add(theme.table()).expandX().widget();
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 20 == 0) refresh();
    }

    private void refresh() {
        if (table == null) return;

        ProgressSnapshot snap = tracker.snapshot();
        table.clear();

        table.add(theme.label(String.format(Locale.ROOT, "Progress: %.1f%%  (%d / %d)",
            snap.percent(), snap.confirmedDropped(), snap.totalRequired()))).expandCellX();
        table.row();
        table.add(theme.label("State: " + snap.state()
            + "   Failed: " + snap.failedOperations()
            + "   Missing: " + snap.missingTypes())).expandCellX();
        table.row();

        List<MaterialProgress> rows = new ArrayList<>(snap.materials());
        rows.sort(comparator());
        if (!filter.isEmpty()) {
            rows.removeIf(m -> !m.itemId().toLowerCase(Locale.ROOT).contains(filter)
                && !m.displayName().toLowerCase(Locale.ROOT).contains(filter));
        }

        if (rows.isEmpty()) {
            table.add(theme.label(snap.materials().isEmpty() ? "No materials configured." : "No materials match the search.")).expandCellX();
            table.row();
            return;
        }

        table.add(theme.horizontalSeparator()).expandX();
        table.row();

        for (MaterialProgress m : rows) {
            table.add(theme.label(m.displayName())).expandCellX();

            Color color = statusColor(m);
            table.add(theme.label(statusText(m)).color(color));
            table.add(theme.label("req " + m.required()));
            table.add(theme.label("done " + m.confirmed()));
            table.add(theme.label("left " + m.remaining()));
            table.add(theme.label("inv " + m.inventory()));
            table.add(theme.label(m.missing() > 0 ? "missing " + m.missing() : "ok")
                .color(m.missing() > 0 ? RED : GREY));
            table.row();
        }
    }

    private Comparator<MaterialProgress> comparator() {
        return switch (sort) {
            case NAME -> Comparator.comparing(MaterialProgress::displayName, String.CASE_INSENSITIVE_ORDER);
            case REQUIRED -> Comparator.comparingInt(MaterialProgress::required).reversed();
            case REMAINING -> Comparator.comparingInt(MaterialProgress::remaining).reversed();
            case STATUS -> Comparator.comparingInt((MaterialProgress m) -> m.complete() ? 1 : 0)
                .thenComparing(Comparator.comparingInt(MaterialProgress::remaining).reversed());
        };
    }

    private Color statusColor(MaterialProgress m) {
        if (m.complete()) return GREEN;
        if (m.missing() > 0) return RED;
        if (m.confirmed() > 0) return AMBER;
        return GREY;
    }

    private String statusText(MaterialProgress m) {
        if (m.complete()) return "Complete";
        if (m.missing() > 0) return "Missing";
        if (m.confirmed() > 0) return "In Progress";
        return "Pending";
    }

    public enum Sort {
        STATUS,
        NAME,
        REQUIRED,
        REMAINING;

        Sort next() {
            Sort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        @Override
        public String toString() {
            return switch (this) {
                case STATUS -> "Status";
                case NAME -> "Name";
                case REQUIRED -> "Required";
                case REMAINING -> "Remaining";
            };
        }
    }
}
