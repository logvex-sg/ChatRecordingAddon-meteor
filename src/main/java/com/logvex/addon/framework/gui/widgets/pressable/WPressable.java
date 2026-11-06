package com.logvex.addon.framework.gui.widgets.pressable;

import com.logvex.addon.framework.gui.widgets.WWidget;

/** A widget that runs an action when clicked. */
public abstract class WPressable extends WWidget {
    public Runnable action;

    protected WPressable(Runnable action) {
        this.action = action;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOver(mouseX, mouseY)) {
            if (action != null) action.run();
            return true;
        }
        return false;
    }
}
