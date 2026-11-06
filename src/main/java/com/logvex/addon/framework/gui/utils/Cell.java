package com.logvex.addon.framework.gui.utils;

import com.logvex.addon.framework.gui.widgets.WWidget;

/**
 * A widget together with the layout hints its container uses.
 *
 * <p>{@code expandX} stretches the widget to the container width; {@code expandCellX} marks the
 * column of a table that absorbs leftover space. Padding is stored as a single value per side.
 */
public class Cell<T extends WWidget> {
    public final T widget;

    public boolean expandX;
    public boolean expandCellX;
    public boolean centerX;
    public double minWidth;
    public double padTop;
    public double padBottom;
    public double padLeft;
    public double padRight;

    public Cell(T widget) {
        this.widget = widget;
    }

    public T widget() {
        return widget;
    }

    public Cell<T> expandX() {
        expandX = true;
        return this;
    }

    public Cell<T> expandCellX() {
        expandCellX = true;
        return this;
    }

    public Cell<T> centerX() {
        centerX = true;
        return this;
    }

    public Cell<T> minWidth(double width) {
        minWidth = width;
        return this;
    }

    public Cell<T> padTop(double pad) {
        padTop = pad;
        return this;
    }

    public Cell<T> padBottom(double pad) {
        padBottom = pad;
        return this;
    }

    public Cell<T> padLeft(double pad) {
        padLeft = pad;
        return this;
    }

    public Cell<T> padRight(double pad) {
        padRight = pad;
        return this;
    }

    public Cell<T> padHorizontal(double pad) {
        padLeft = pad;
        padRight = pad;
        return this;
    }

    public Cell<T> padVertical(double pad) {
        padTop = pad;
        padBottom = pad;
        return this;
    }

    public Cell<T> pad(double pad) {
        return padHorizontal(pad).padVertical(pad);
    }
}
