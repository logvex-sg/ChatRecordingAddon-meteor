package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import net.minecraft.client.gui.DrawContext;

/**
 * A single child laid out on a filled row.
 *
 * <p>Rows in the module and HUD lists are wrapped in a card so the list reads as a stack of cards
 * instead of floating text, which is what makes a long list scannable. The card paints its fill
 * (lighter under the pointer) and insets the child by a small padding, so every row lines up without
 * each screen repeating the arithmetic.
 */
public class WCard extends WContainer {
    /** Inner padding on each side of the child. */
    public static final double PAD = 5;
    private static final int RADIUS = 3;

    /** Highlights the card while the pointer is over it, even when the pointer is between widgets. */
    public boolean highlightOnHover = true;

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        return super.add(widget);
    }

    @Override
    public void calculateSize() {
        double width = 0;
        double height = 0;
        for (Cell<? extends WWidget> cell : cells) {
            cell.widget.calculateSize();
            width = Math.max(width, cell.widget.width + cell.padLeft + cell.padRight);
            height = Math.max(height, cell.widget.height + cell.padTop + cell.padBottom);
        }
        this.width = width + PAD * 2;
        this.height = height + PAD * 2;
    }

    @Override
    public void layout(double x, double y, double width) {
        super.layout(x, y, width);
        for (Cell<? extends WWidget> cell : cells) {
            double available = this.width - PAD * 2;
            double childWidth = cell.expandX ? available : Math.min(cell.widget.width, available);
            cell.widget.layout(this.x + PAD, this.y + PAD, childWidth);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        boolean hovered = highlightOnHover && isMouseOver(mouseX, mouseY);
        if (theme != null) {
            int fill = argb(hovered ? theme.rowHover : theme.row);
            context.fill((int) x, (int) y, (int) (x + width), (int) (y + height), fill);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return ((c.a == 0 ? 255 : c.a) << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
