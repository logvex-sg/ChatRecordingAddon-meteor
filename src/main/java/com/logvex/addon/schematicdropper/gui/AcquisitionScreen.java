package com.logvex.addon.schematicdropper.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.schematicdropper.SchematicDropperModule;
import com.logvex.addon.schematicdropper.core.AcquisitionSnapshot;
import com.logvex.addon.schematicdropper.core.AcquisitionState;
import com.logvex.addon.schematicdropper.core.MaterialAcquisition;
import com.logvex.addon.schematicdropper.core.MaterialListImporter;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Live view of the Creative acquisition workflow.
 *
 * <p>Every number comes from {@link AcquisitionSnapshot}, which the engine builds from the live
 * inventory and from server-confirmed gives only. The screen never fabricates progress: a material
 * with no measurement yet shows {@code --} rather than a misleading zero.
 */
public class AcquisitionScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color AMBER = new Color(240, 200, 110);
    private static final Color GREY = new Color(170, 170, 170);

    private final SchematicDropperModule module;

    private WTextBox search;
    private WTable table;

    private String filter = "";

    public AcquisitionScreen(SchematicDropperModule module) {
        this(GuiThemes.get(), module);
    }

    public AcquisitionScreen(GuiTheme theme, SchematicDropperModule module) {
        super(theme, "Creative Acquisition");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        add(theme.label("Automatic Creative Acquisition")).expandX();
        add(theme.horizontalSeparator()).expandX();

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        WButton start = controls.add(theme.button("Start")).widget();
        start.action = module::startAcquisition;
        WButton pause = controls.add(theme.button("Pause")).widget();
        pause.action = module::pauseAcquisition;
        WButton resume = controls.add(theme.button("Resume")).widget();
        resume.action = module::resumeAcquisition;
        WButton stop = controls.add(theme.button("Stop")).widget();
        stop.action = module::stopAcquisition;

        WHorizontalList controls2 = add(theme.horizontalList()).expandX().widget();
        WButton retry = controls2.add(theme.button("Retry Failed")).widget();
        retry.action = module::retryAcquisition;
        WButton restart = controls2.add(theme.button("Restart Current")).widget();
        restart.action = module::restartCurrentAcquisition;
        WButton importButton = controls2.add(theme.button("Import File...")).widget();
        importButton.action = () -> {
            module.importMaterialFile();
            refresh();
        };

        search = add(theme.textBox("", "Search materials...")).minWidth(400).expandX().widget();
        search.action = () -> {
            filter = search.get().trim().toLowerCase(Locale.ROOT);
            refresh();
        };

        add(theme.horizontalSeparator()).expandX();
        table = add(theme.table()).expandX().widget();
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 10 == 0) refresh();
    }

    private void refresh() {
        if (table == null) return;

        AcquisitionSnapshot snap = module.acquisitionSnapshot();
        table.clear();

        table.add(theme.label(String.format(Locale.ROOT, "Progress: %.1f%%  (%s / %s)",
            snap.percent(), Messages.number(snap.totalAcquired()),
            Messages.number(snap.totalRequired())))).expandCellX();
        table.row();
        table.add(theme.label("State: " + snap.state()
            + "   Materials: " + snap.materialsComplete() + "/" + snap.materialsTotal()
            + "   Failed: " + snap.failedOperations())).expandCellX();
        table.row();

        MaterialListImporter.Result imported = module.lastImport();
        if (imported != null) {
            String summary = "Imported: " + module.lastImportName()
                + "   " + Messages.number(imported.requirements().size()) + " materials"
                + "   " + Messages.number(imported.totalRequired()) + " items"
                + (imported.invalid().isEmpty() ? ""
                    : "   " + Messages.plural(imported.invalid().size(), "invalid entry", "invalid entries"));
            table.add(theme.label(summary)).expandCellX();
            table.row();
        }

        String current = snap.currentMaterial() == null
            ? "Current: none"
            : "Current: " + Messages.prettyId(snap.currentMaterial())
                + "  (slot " + snap.currentSlot() + ", " + snap.currentAmount() + ")";
        table.add(theme.label(current)).expandCellX();
        table.row();

        List<MaterialAcquisition> rows = new ArrayList<>(snap.materials());
        rows.sort(Comparator.comparingInt((MaterialAcquisition m) -> m.complete() ? 1 : 0)
            .thenComparing(MaterialAcquisition::displayName, String.CASE_INSENSITIVE_ORDER));
        if (!filter.isEmpty()) {
            rows.removeIf(m -> !m.itemId().toLowerCase(Locale.ROOT).contains(filter)
                && !m.displayName().toLowerCase(Locale.ROOT).contains(filter));
        }

        if (rows.isEmpty()) {
            table.add(theme.label(snap.materials().isEmpty()
                ? "No materials imported. Use Import File... to load a material list."
                : "No materials match the search.")).expandCellX();
            table.row();
            return;
        }

        table.add(theme.horizontalSeparator()).expandX();
        table.row();
        table.add(theme.label("Material")).expandCellX();
        table.add(theme.label("Status"));
        table.add(theme.label("Required"));
        table.add(theme.label("Held"));
        table.add(theme.label("Needed"));
        table.add(theme.label("Acquired"));
        table.row();

        for (MaterialAcquisition m : rows) {
            table.add(theme.label(Messages.truncate(m.displayName(), 28))).expandCellX();
            table.add(theme.label(statusText(m)).color(statusColor(m)));
            table.add(theme.label(Messages.number(m.required())));
            table.add(theme.label(m.held() < 0 ? "--" : Messages.number(m.held())));
            table.add(theme.label(Messages.number(m.needed())));
            table.add(theme.label(Messages.number(m.acquired())));
            table.row();
        }
    }

    private Color statusColor(MaterialAcquisition m) {
        if (m.complete()) return GREEN;
        if (m.acquired() > 0) return AMBER;
        return GREY;
    }

    private String statusText(MaterialAcquisition m) {
        if (m.complete()) return "Complete";
        if (m.acquired() > 0) return "In Progress";
        return "Pending";
    }
}
