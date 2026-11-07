package com.logvex.addon.world.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;

import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.world.core.LocationCategory;
import com.logvex.addon.world.core.WorldFormat;
import com.logvex.addon.world.core.WorldPoint;
import com.logvex.addon.world.core.WorldStore;
import com.logvex.addon.world.mc.WorldService;

import java.nio.file.Path;
import java.util.List;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * A searchable list of every remembered place in the current world.
 *
 * <p>Search by name, category or note; filter by category; navigate to a point; delete it; or export
 * and import the whole database. It reads the live world database on each rebuild, so it always
 * shows what was actually recorded.
 */
public class LocationDatabaseScreen extends WindowScreen {
    private static final Color GREY = new Color(170, 170, 170);
    private static final Color GREEN = new Color(150, 220, 150);
    private static final Color RED = new Color(255, 140, 140);

    private String query = "";
    private LocationCategory filter;
    private String status = "";

    public LocationDatabaseScreen() {
        this(GuiThemes.get());
    }

    public LocationDatabaseScreen(GuiTheme theme) {
        super(theme, "Location Database");
    }

    @Override
    public void initWidgets() {
        var profile = WorldService.get().intelligence().current();
        if (profile == null) {
            add(theme.label("No world is loaded yet.")).expandX();
            add(theme.label("Join a world to start recording places.", GREY)).expandX();
            return;
        }

        add(theme.label("World: " + profile.label())).expandX();
        add(theme.label("Known places: " + profile.locations().size(), GREY)).expandX();
        if (!status.isEmpty()) add(theme.label(status, GREEN)).expandX();

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        WTextBox search = controls.add(theme.textBox(query, "Search name, category or note")).expandX().widget();
        search.action = () -> {
            query = search.get();
            reload();
        };
        controls.add(theme.button("Export")).widget().action = this::exportAll;
        controls.add(theme.button("Import")).widget().action = this::importAll;

        WHorizontalList filters = add(theme.horizontalList()).expandX().widget();
        filters.add(theme.button("All")).widget().action = () -> {
            filter = null;
            reload();
        };
        for (LocationCategory category : LocationCategory.values()) {
            filters.add(theme.button(category.toString())).widget().action = () -> {
                filter = category;
                reload();
            };
        }

        add(theme.horizontalSeparator()).expandX();
        WTable table = add(theme.table()).expandX().widget();

        List<WorldPoint> points = WorldService.get().intelligence().search(query, filter, null);
        if (points.isEmpty()) {
            add(theme.label(query.isBlank() ? "Nothing recorded yet." : "No places match \"" + query + "\".", GREY))
                .expandX();
            return;
        }

        for (WorldPoint point : points) {
            table.add(theme.label(point.name().isBlank() ? "(unnamed)" : point.name())).expandCellX();
            table.add(theme.label(point.category().toString(), GREY));
            table.add(theme.label(WorldFormat.coords(point.x(), point.y(), point.z())));
            table.add(theme.label(WorldFormat.dimension(point.dimension()), GREY));
            table.add(theme.label("seen " + point.visits() + "x", GREY));
            WButton go = table.add(theme.button("Go")).widget();
            go.action = () -> navigate(point);
            WButton delete = table.add(theme.button("X")).widget();
            delete.action = () -> {
                profile.locations().remove(point);
                WorldService.get().saveAsync();
                reload();
            };
            table.row();
        }
        table.finish();
    }

    private void navigate(WorldPoint point) {
        var nav = com.logvex.addon.framework.pathing.PathManagers.get();
        if (nav == null || "none".equals(nav.getName())) {
            status = "No navigation backend is installed (Baritone is not present).";
            reload();
            return;
        }
        nav.moveTo(new net.minecraft.util.math.BlockPos(point.x(), point.y(), point.z()));
        close();
    }

    private void exportAll() {
        Path file = exportFile();
        boolean ok = WorldStore.exportTo(file, WorldService.get().intelligence().exportMap());
        status = ok ? "Exported to " + file.getFileName() + "." : "Export failed.";
        reload();
    }

    private void importAll() {
        Path file = exportFile();
        var map = WorldStore.readExport(file);
        if (map == null) {
            status = "No export file found at " + file.getFileName() + ".";
            reload();
            return;
        }
        WorldService.get().intelligence().importMap(map);
        WorldService.get().saveAsync();
        status = "Imported from " + file.getFileName() + ".";
        reload();
    }

    private static Path exportFile() {
        return com.logvex.addon.framework.LogClientContext.FOLDER().toPath()
            .resolve("logclient").resolve("world-intelligence-export.json");
    }

    @Override
    public void tick() {
        super.tick();
        // Refresh periodically so a place discovered while the screen is open appears.
        if (mc != null && mc.player != null && mc.player.age % 40 == 0) reload();
    }
}
