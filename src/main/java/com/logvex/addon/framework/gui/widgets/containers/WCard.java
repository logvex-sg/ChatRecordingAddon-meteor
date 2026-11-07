package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.gui.animation.Animator;
import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.gui.DrawContext;

/**
 * A single child laid out on a filled row.
 *
 * <p>Rows in the module and HUD lists are wrapped in a card so the list reads as a stack of cards
 * instead of floating text, which is what makes a long list scannable. The card paints its fill
 * (lightened under the pointer), draws a hairline border and insets the child by a small padding, so
 * every row lines up without each screen repeating the arithmetic.
 *
 * <p>The highlight is eased rather than switched, and a card can be marked
 * {@linkplain #accentBar accent-barred} to draw a vertical strip in the accent colour down its left
 * edge, which is how an enabled entry is marked in the list.
 */
public class WCard extends WContainer {
    /** Inner padding on each side of the child. */
    public static final double PAD = 5;
    private static final double RADIUS = 4;

    /** Highlights the card while the pointer is over it, even when the pointer is between widgets. */
    public boolean highlightOnHover = true;
    /** Draws an accent-coloured strip down the left edge. */
    public boolean accentBar;

    private final Animator hover = Animator.of(0);

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
        double inset = accentBar ? 3 : PAD;
        for (Cell<? extends WWidget> cell : cells) {
            double available = this.width - PAD - inset;
            double childWidth = cell.expandX ? available : Math.min(cell.widget.width, available);
            cell.widget.layout(this.x + inset, this.y + PAD, childWidth);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        boolean hovered = highlightOnHover && isMouseOver(mouseX, mouseY);
        hover.target(hovered ? 1 : 0);
        hover.update(AnimationClock.delta());

        if (theme != null) {
            double t = hover.value();
            int fill = Render2D.blend(Render2D.argb(theme.row), Render2D.argb(theme.rowHover), t);
            Render2D.roundedRect(context, x, y, width, height, RADIUS, fill);
            Render2D.roundedOutline(context, x, y, width, height, RADIUS,
                Render2D.blend(Render2D.argb(theme.separator, 90), Render2D.argb(theme.accent, 170), t), 1);
            if (accentBar) {
                Render2D.gradientVertical(context, x + 1, y + 2, 2, height - 4,
                    Render2D.argb(theme.accent), Render2D.argb(theme.accentSecondary));
            }
            if (t > 0.01) {
                Render2D.glow(context, x, y, width, height, RADIUS,
                    Render2D.argb(theme.glow, (int) (70 * t)), 3, 2);
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
