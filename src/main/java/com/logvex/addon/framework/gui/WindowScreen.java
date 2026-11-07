package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WWindow;

/** A {@link WidgetScreen} whose content lives in a single titled window. */
public abstract class WindowScreen extends WidgetScreen {
    protected final WWindow window;

    protected WindowScreen(GuiTheme theme, WWidget icon, String title) {
        super(theme, title);
        window = new WWindow(title);
        window.theme = theme;
        window.view.theme = theme;
    }

    /**
     * Re-attaches the window frame. The root is cleared on every reload, so the frame has to be added
     * back or the screen would render nothing after its first interaction.
     */
    @Override
    protected void populateRoot() {
        window.clear();
        root.add(window).expandX();
    }

    protected WindowScreen(GuiTheme theme, String title) {
        this(theme, null, title);
    }

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        widget.theme = theme;
        return window.add(widget);
    }

    @Override
    public void clear() {
        window.clear();
    }
}
