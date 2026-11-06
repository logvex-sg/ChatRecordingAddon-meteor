package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import net.minecraft.client.gui.DrawContext;

/**
 * Base class for every GUI element.
 *
 * <p>Layout is two-pass and repeated each frame: {@link #calculateSize()} computes a preferred
 * size, then {@link #layout} places the widget at a position and a width chosen by its container.
 * Widgets never cache a position across frames, so a resize or a change in content is picked up
 * immediately.
 */
public abstract class WWidget {
    public GuiTheme theme;

    public double x;
    public double y;
    public double width;
    public double height;

    public void calculateSize() {
    }

    /** Positions the widget. {@code width} is the space offered by the container, or -1 to keep it. */
    public void layout(double x, double y, double width) {
        this.x = x;
        this.y = y;
        if (width >= 0) this.width = width;
    }

    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    /** A drag in progress, so a slider can follow the pointer while the button is held. */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return false;
    }

    public void mouseMoved(double mouseX, double mouseY) {
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        return false;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
