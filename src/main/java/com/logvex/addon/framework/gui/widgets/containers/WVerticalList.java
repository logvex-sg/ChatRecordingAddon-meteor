package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Stacks children top to bottom.
 *
 * <p>A cell with {@code expandX} is given the full container width; every other cell keeps its own
 * preferred width. {@code minWidth} raises a widget's preferred width so a search box can ask to be
 * wide without stretching.
 *
 * <p>When {@link #maxHeight} is set the list becomes a viewport: content taller than the limit is
 * clipped and reached by scrolling, which is what keeps a long settings page usable.
 */
public class WVerticalList extends WContainer {
    public double spacing = 2;
    /** Viewport height; {@link Double#MAX_VALUE} means "grow to fit the content". */
    public double maxHeight = Double.MAX_VALUE;

    private double contentHeight;
    private double scroll;

    public double scroll() {
        return scroll;
    }

    @Override
    public void calculateSize() {
        for (Cell<? extends WWidget> cell : cells) cell.widget.calculateSize();

        double w = 0;
        double h = 0;
        for (Cell<? extends WWidget> cell : cells) {
            w = Math.max(w, Math.max(cell.widget.width, cell.minWidth) + cell.padLeft + cell.padRight);
            h += cell.widget.height + cell.padTop + cell.padBottom + spacing;
        }
        if (!cells.isEmpty()) h -= spacing;

        contentHeight = h;
        width = w;
        height = Math.min(h, maxHeight);
        clampScroll();
    }

    private void clampScroll() {
        double max = Math.max(0, contentHeight - height);
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
    }

    @Override
    public void layout(double x, double y, double width) {
        super.layout(x, y, width);
        double cursor = this.y - scroll;
        for (Cell<? extends WWidget> cell : cells) {
            cursor += cell.padTop;
            double available = this.width - cell.padLeft - cell.padRight;
            double childWidth = cell.expandX ? available : Math.min(cell.widget.width, available);
            double childX = this.x + cell.padLeft;
            if (cell.centerX && childWidth < available) {
                childX += (available - childWidth) / 2;
            }
            cell.widget.layout(childX, cursor, childWidth);
            cursor += cell.widget.height + cell.padBottom + spacing;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        boolean clipped = contentHeight > height;
        if (clipped) {
            context.enableScissor((int) x, (int) y, (int) (x + width), (int) (y + height));
        }
        super.render(context, mouseX, mouseY, delta);
        if (clipped) {
            context.disableScissor();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (contentHeight > height && isMouseOver(mouseX, mouseY)) {
            scroll -= amount * 12;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (contentHeight > height && !isMouseOver(mouseX, mouseY)) return false;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
