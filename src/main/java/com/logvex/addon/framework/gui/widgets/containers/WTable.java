package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A grid whose columns are sized from their widest cell.
 *
 * <p>Cells are added left to right and {@link #row()} ends the current row. Columns are as wide as
 * their widest widget, except a cell marked {@code expandCellX}, which shares the leftover width.
 */
public class WTable extends WContainer {
    public double spacing = 3;

    private final List<List<Cell<? extends WWidget>>> rows = new ArrayList<>();
    private List<Cell<? extends WWidget>> current = new ArrayList<>();
    private int columns;

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        Cell<W> cell = super.add(widget);
        current.add(cell);
        columns = Math.max(columns, current.size());
        return cell;
    }

    public void row() {
        if (current.isEmpty()) return;
        rows.add(current);
        current = new ArrayList<>();
    }

    /** Ends the last row; call after filling the table. */
    public void finish() {
        row();
    }

    @Override
    public void clear() {
        super.clear();
        rows.clear();
        current = new ArrayList<>();
        columns = 0;
    }

    @Override
    public void calculateSize() {
        row();
        for (List<Cell<? extends WWidget>> r : rows) {
            for (Cell<? extends WWidget> cell : r) cell.widget.calculateSize();
        }

        double totalHeight = 0;
        for (List<Cell<? extends WWidget>> r : rows) {
            double rowHeight = 0;
            for (Cell<? extends WWidget> cell : r) {
                rowHeight = Math.max(rowHeight, cell.widget.height + cell.padTop + cell.padBottom);
            }
            totalHeight += rowHeight + spacing;
        }
        if (!rows.isEmpty()) totalHeight -= spacing;

        double totalWidth = 0;
        for (int col = 0; col < columns; col++) {
            double colWidth = 0;
            for (List<Cell<? extends WWidget>> r : rows) {
                if (col >= r.size()) continue;
                Cell<? extends WWidget> cell = r.get(col);
                colWidth = Math.max(colWidth, Math.max(cell.widget.width, cell.minWidth) + cell.padLeft + cell.padRight);
            }
            totalWidth += colWidth + spacing;
        }
        if (columns > 0) totalWidth -= spacing;

        width = totalWidth;
        height = totalHeight;
    }

    @Override
    public void layout(double x, double y, double width) {
        super.layout(x, y, width);

        double[] colWidths = new double[columns];
        int expandColumns = 0;
        for (int col = 0; col < columns; col++) {
            double colWidth = 0;
            boolean expand = false;
            for (List<Cell<? extends WWidget>> r : rows) {
                if (col >= r.size()) continue;
                Cell<? extends WWidget> cell = r.get(col);
                colWidth = Math.max(colWidth, Math.max(cell.widget.width, cell.minWidth) + cell.padLeft + cell.padRight);
                if (cell.expandCellX) expand = true;
            }
            colWidths[col] = colWidth;
            if (expand) expandColumns++;
        }

        double used = 0;
        for (double c : colWidths) used += c;
        used += spacing * Math.max(0, columns - 1);

        if (expandColumns > 0 && this.width > used) {
            double extra = (this.width - used) / expandColumns;
            for (int col = 0; col < columns; col++) {
                for (List<Cell<? extends WWidget>> r : rows) {
                    if (col < r.size() && r.get(col).expandCellX) {
                        colWidths[col] += extra;
                        break;
                    }
                }
            }
        }

        double cursorY = this.y;
        for (List<Cell<? extends WWidget>> r : rows) {
            double rowHeight = 0;
            for (Cell<? extends WWidget> cell : r) {
                rowHeight = Math.max(rowHeight, cell.widget.height + cell.padTop + cell.padBottom);
            }

            double cursorX = this.x;
            for (int col = 0; col < r.size(); col++) {
                Cell<? extends WWidget> cell = r.get(col);
                double available = colWidths[col] - cell.padLeft - cell.padRight;
                double childWidth = cell.expandCellX ? available : Math.min(cell.widget.width, available);
                double childX = cursorX + cell.padLeft;
                if (cell.centerX && childWidth < available) childX += (available - childWidth) / 2;
                cell.widget.layout(childX, cursorY + cell.padTop, childWidth);
                cursorX += colWidths[col] + spacing;
            }
            cursorY += rowHeight + spacing;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        for (List<Cell<? extends WWidget>> r : rows) {
            for (Cell<? extends WWidget> cell : r) {
                cell.widget.render(context, mouseX, mouseY, delta);
            }
        }
    }
}
