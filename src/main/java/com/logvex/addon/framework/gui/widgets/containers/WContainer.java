package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Base class for widgets that hold children. */
public abstract class WContainer extends WWidget {
    public final List<Cell<? extends WWidget>> cells = new ArrayList<>();

    public <W extends WWidget> Cell<W> add(W widget) {
        Cell<W> cell = new Cell<>(widget);
        widget.theme = theme;
        cells.add(cell);
        return cell;
    }

    public void clear() {
        cells.clear();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    @Override
    public void calculateSize() {
        for (Cell<? extends WWidget> cell : cells) cell.widget.calculateSize();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        for (Cell<? extends WWidget> cell : cells) {
            cell.widget.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Cell<? extends WWidget> cell : cells) {
            if (cell.widget.mouseClicked(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (Cell<? extends WWidget> cell : cells) {
            if (cell.widget.mouseReleased(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        for (Cell<? extends WWidget> cell : cells) {
            if (cell.widget.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        }
        return false;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        for (Cell<? extends WWidget> cell : cells) {
            cell.widget.mouseMoved(mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        for (Cell<? extends WWidget> cell : cells) {
            if (cell.widget.mouseScrolled(mouseX, mouseY, amount)) return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (Cell<? extends WWidget> cell : cells) {
            if (cell.widget.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        for (Cell<? extends WWidget> cell : cells) {
            if (cell.widget.charTyped(chr, modifiers)) return true;
        }
        return false;
    }
}
