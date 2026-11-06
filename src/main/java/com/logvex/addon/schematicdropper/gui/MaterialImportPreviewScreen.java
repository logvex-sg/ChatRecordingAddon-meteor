package com.logvex.addon.schematicdropper.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.schematicdropper.SchematicDropperModule;
import com.logvex.addon.schematicdropper.core.MaterialListImporter;
import com.logvex.addon.schematicdropper.core.Requirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows exactly what an import produced before anything is acquired or dropped.
 *
 * <p>The point is trust: a large material report is easy to get subtly wrong, so the user is shown
 * the entry count, the total, how many duplicates were merged and how many lines were rejected, then
 * the whole ordered list. Nothing is started until the user presses Start, so a mis-import costs a
 * click rather than a wrong build.
 */
public class MaterialImportPreviewScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color AMBER = new Color(240, 200, 110);
    private static final Color RED = new Color(240, 120, 120);

    /** Rows drawn per page so a thousand-entry list stays responsive. */
    private static final int PAGE_SIZE = 100;

    private final SchematicDropperModule module;
    private final MaterialListImporter.Result result;

    private String filter = "";
    private int page;

    public MaterialImportPreviewScreen(SchematicDropperModule module, MaterialListImporter.Result result) {
        super(GuiThemes.get(), "Import Preview");
        this.module = module;
        this.result = result;
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        if (result == null) {
            add(theme.label("Nothing has been imported yet.").color(theme.muted)).expandX();
            add(backButton()).expandX();
            return;
        }

        add(theme.horizontalSeparator(module.lastImportName())).expandX();

        WTable summary = add(theme.table()).expandX().widget();
        summary.add(theme.label("Materials")).expandCellX();
        summary.add(theme.label(Messages.number(result.requirements().size())));
        summary.row();
        summary.add(theme.label("Total items")).expandCellX();
        summary.add(theme.label(Messages.number(result.totalRequired())));
        summary.row();
        summary.add(theme.label("Valid entries")).expandCellX();
        summary.add(theme.label(Messages.number(result.validEntries())).color(GREEN));
        summary.row();
        summary.add(theme.label("Duplicates merged")).expandCellX();
        summary.add(theme.label(Messages.number(result.duplicateIdsMerged())).color(AMBER));
        summary.row();
        summary.add(theme.label("Invalid entries")).expandCellX();
        summary.add(theme.label(Messages.number(result.invalid().size()))
            .color(result.invalid().isEmpty() ? theme.text : RED));
        summary.row();

        List<Requirement> all = result.requirements();
        if (!all.isEmpty()) {
            summary.add(theme.label("First entry")).expandCellX();
            summary.add(theme.label(Messages.truncate(all.get(0).itemId(), 40)));
            summary.row();
            summary.add(theme.label("Last entry")).expandCellX();
            summary.add(theme.label(Messages.truncate(all.get(all.size() - 1).itemId(), 40)));
            summary.row();
        }

        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton start = actions.add(theme.button("Start Acquisition")).widget();
        start.action = module::startAcquisition;
        WButton startDrop = actions.add(theme.button("Start Drop Run")).widget();
        startDrop.action = module::startRun;
        actions.add(backButton());

        WTextBox search = add(theme.textBox(filter, "Filter materials...")).expandX().widget();
        search.action = () -> {
            filter = search.get().trim().toLowerCase(Locale.ROOT);
            page = 0;
            reload();
        };

        add(theme.horizontalSeparator("Imported materials")).expandX();
        renderList(theme);
    }

    private void renderList(GuiTheme theme) {
        List<Requirement> rows = new ArrayList<>(result.requirements());
        if (!filter.isEmpty()) {
            rows.removeIf(r -> !r.itemId().toLowerCase(Locale.ROOT).contains(filter)
                && !r.displayName().toLowerCase(Locale.ROOT).contains(filter));
        }

        if (rows.isEmpty()) {
            add(theme.label(result.requirements().isEmpty()
                ? "The file contained no usable materials."
                : "No materials match the filter.").color(theme.muted)).expandX();
            return;
        }

        int totalPages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, totalPages - 1);
        int from = page * PAGE_SIZE;
        int to = Math.min(rows.size(), from + PAGE_SIZE);

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("#"));
        table.add(theme.label("Material")).expandCellX();
        table.add(theme.label("Required"));
        table.row();

        for (int i = from; i < to; i++) {
            Requirement r = rows.get(i);
            table.add(theme.label(Messages.number(i + 1)));
            table.add(theme.label(Messages.truncate(r.displayName(), 40))).expandCellX();
            table.add(theme.label(Messages.number(r.required())));
            table.row();
        }

        if (totalPages > 1) {
            WHorizontalList pages = add(theme.horizontalList()).expandX().widget();
            WButton prev = pages.add(theme.button("Previous")).widget();
            prev.action = () -> {
                if (page > 0) {
                    page--;
                    reload();
                }
            };
            pages.add(theme.label("Page " + (page + 1) + " / " + totalPages)).widget().color(theme.muted);
            WButton next = pages.add(theme.button("Next")).widget();
            next.action = () -> {
                if (page < totalPages - 1) {
                    page++;
                    reload();
                }
            };
        }
    }

    private WButton backButton() {
        WButton back = theme.button("Close", this::close);
        return back;
    }
}
