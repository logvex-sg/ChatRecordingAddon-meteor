package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;

/** Lays children left to right; cells with {@code expandX} share the leftover width. */
public class WHorizontalList extends WContainer {
    public double spacing = 2;

    @Override
    public void calculateSize() {
        super.calculateSize();
        double w = 0;
        double h = 0;
        for (Cell<? extends WWidget> cell : cells) {
            w += Math.max(cell.widget.width, cell.minWidth) + cell.padLeft + cell.padRight + spacing;
            h = Math.max(h, cell.widget.height + cell.padTop + cell.padBottom);
        }
        if (!cells.isEmpty()) w -= spacing;
        width = w;
        height = h;
    }

    @Override
    public void layout(double x, double y, double width) {
        super.layout(x, y, width);

        double fixed = 0;
        int expandCount = 0;
        for (Cell<? extends WWidget> cell : cells) {
            fixed += cell.padLeft + cell.padRight + spacing;
            if (cell.expandX) {
                expandCount++;
            } else {
                fixed += Math.max(cell.widget.width, cell.minWidth);
            }
        }
        if (!cells.isEmpty()) fixed -= spacing;

        double each = expandCount > 0 ? Math.max(0, (this.width - fixed) / expandCount) : 0;

        double cursor = this.x;
        for (Cell<? extends WWidget> cell : cells) {
            cursor += cell.padLeft;
            double childWidth = cell.expandX ? each : Math.max(cell.widget.width, cell.minWidth);
            cell.widget.layout(cursor, this.y + cell.padTop, childWidth);
            cursor += childWidth + cell.padRight + spacing;
        }
    }
}
