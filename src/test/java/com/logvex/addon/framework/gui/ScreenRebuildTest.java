package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.gui.widgets.WWidget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A screen rebuild must not lose the parts of the tree that are not recreated by {@code initWidgets}.
 *
 * <p>This covers a real regression: {@link WidgetScreen#reload()} clears the root before rebuilding,
 * so a {@link WindowScreen} lost its window frame on the first rebuild and rendered an empty screen.
 * That made every window-based screen (the file browser, the import preview, the log viewer) appear
 * to do nothing after the first click.
 */
class ScreenRebuildTest {

    /** A widget with no Minecraft dependency, so the layout maths can run in a unit test. */
    private static final class FakeWidget extends WWidget {
        FakeWidget(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }

    private static WindowScreen screenWithOneRow() {
        return new WindowScreen(GuiThemes.get(), "Test") {
            @Override
            public void initWidgets() {
                add(new FakeWidget(40, 10)).expandX();
            }
        };
    }

    @Test
    @DisplayName("a window screen keeps its frame and content across repeated rebuilds")
    void windowFrameSurvivesReload() {
        WindowScreen screen = screenWithOneRow();

        screen.reload();
        assertEquals(1, screen.root.cells.size(), "the window frame must be attached after a rebuild");
        assertEquals(1, screen.window.view.cells.size(), "the content row must be present");

        // A second rebuild must replace the content rather than append to it.
        screen.reload();
        assertEquals(1, screen.root.cells.size(), "the frame must not be duplicated");
        assertEquals(1, screen.window.view.cells.size(), "content must not accumulate across rebuilds");
    }

    @Test
    @DisplayName("a rebuild lays the new widgets out instead of leaving them at the origin")
    void reloadLaysOutWidgets() {
        WindowScreen screen = screenWithOneRow();

        screen.reload();

        WWidget row = screen.window.view.cells.get(0).widget;
        assertTrue(row.width > 0, "an expandX row must be given the container width");
        assertEquals(10, row.x, "the row must be placed at the list's left margin, not at 0");
    }
}
