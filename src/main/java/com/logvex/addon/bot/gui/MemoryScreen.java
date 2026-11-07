package com.logvex.addon.bot.gui;

import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotMemory;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * Shows the bot's stored knowledge: waypoints, known places and past objectives.
 *
 * <p>Read-only apart from the clear buttons, so a user can inspect what the bot remembers without
 * risking an accidental edit.
 */
public class MemoryScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color GREY = new Color(170, 170, 170);

    private final BotModule module;

    private WTable waypointTable;
    private WTable placeTable;
    private WTable objectiveTable;

    public MemoryScreen(BotModule module) {
        this(GuiThemes.get(), module);
    }

    public MemoryScreen(GuiTheme theme, BotModule module) {
        super(theme, "Bot Memory");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        WButton save = controls.add(theme.button("Save Now")).expandX().widget();
        save.action = () -> {
            module.memory();
            module.info("Bot memory saved.");
        };
        WButton clearWaypoints = controls.add(theme.button("Clear Waypoints")).expandX().widget();
        clearWaypoints.action = () -> {
            BotMemory memory = module.memory();
            for (BotMemory.Waypoint waypoint : memory.waypoints()) memory.removeWaypoint(waypoint.name());
            refresh();
        };
        WButton clearAll = controls.add(theme.button("Clear All")).expandX().widget();
        clearAll.action = () -> {
            module.memory().clear();
            refresh();
        };

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Waypoints")).expandX();
        waypointTable = add(theme.table()).expandX().widget();

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Known Places")).expandX();
        placeTable = add(theme.table()).expandX().widget();

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Past Objectives")).expandX();
        objectiveTable = add(theme.table()).expandX().widget();

        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 20 == 0) refresh();
    }

    private void refresh() {
        BotMemory memory = module.memory();
        if (waypointTable == null || memory == null) return;

        waypointTable.clear();
        List<BotMemory.Waypoint> waypoints = memory.waypoints();
        if (waypoints.isEmpty()) {
            waypointTable.add(theme.label("No waypoints saved.")).expandCellX();
            waypointTable.row();
        } else {
            for (BotMemory.Waypoint waypoint : waypoints) {
                waypointTable.add(theme.label(waypoint.name())).expandCellX();
                waypointTable.add(theme.label(waypoint.describe()));
                waypointTable.add(theme.label(Messages.ago(waypoint.createdAt(), System.currentTimeMillis()))
                    .color(GREY));
                waypointTable.row();
            }
        }

        placeTable.clear();
        List<BotMemory.KnownPlace> places = memory.places(null);
        if (places.isEmpty()) {
            placeTable.add(theme.label("No places recorded.")).expandCellX();
            placeTable.row();
        } else {
            int shown = 0;
            for (BotMemory.KnownPlace place : places) {
                if (shown++ >= 20) break;
                placeTable.add(theme.label(place.kind())).expandCellX();
                placeTable.add(theme.label(place.label()));
                placeTable.add(theme.label(place.x() + ", " + place.y() + ", " + place.z()));
                placeTable.row();
            }
        }

        objectiveTable.clear();
        List<BotMemory.ObjectiveRecord> objectives = memory.objectives();
        if (objectives.isEmpty()) {
            objectiveTable.add(theme.label("No objectives recorded.")).expandCellX();
            objectiveTable.row();
        } else {
            int shown = 0;
            for (BotMemory.ObjectiveRecord record : objectives) {
                if (shown++ >= 20) break;
                objectiveTable.add(theme.label(Messages.clock(record.at()))).widget();
                objectiveTable.add(theme.label(record.description())).expandCellX();
                objectiveTable.add(theme.label(record.success() ? "Done" : "Failed")
                    .color(record.success() ? GREEN : RED));
                objectiveTable.row();
            }
        }
    }
}
