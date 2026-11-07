package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.gui.animation.Animator;
import com.logvex.addon.framework.gui.widgets.pressable.WPressable;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * One entry in the navigation rail.
 *
 * <p>Draws a small glyph, a label and, when selected, a filled accent bar and a glow. The selection
 * and hover states are eased so moving between tabs slides rather than snaps. The glyph is drawn from
 * primitives rather than a texture, so the rail needs no art assets and picks up the active theme.
 */
public class WNavItem extends WPressable {
    /** A tiny vector-ish glyph for a rail entry. */
    public enum Glyph {
        MODULES, HUD, THEME, SETTINGS, INFO
    }

    private static final double RADIUS = 3;

    private final String label;
    private final Glyph glyph;
    private boolean selected;

    private final Animator hover = Animator.of(0);
    private final Animator select = Animator.of(0);

    public WNavItem(String label, Glyph glyph, boolean selected, Runnable action) {
        super(action);
        this.label = label == null ? "" : label;
        this.glyph = glyph == null ? Glyph.MODULES : glyph;
        this.selected = selected;
        // Start already selected so the first frame does not animate in from the left.
        this.select.snap(selected ? 1 : 0);
    }

    /** Marks the item as the current page, without rebuilding it. */
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
    }

    @Override
    public void calculateSize() {
        height = Math.max(20, MinecraftClient.getInstance().textRenderer.fontHeight + 10);
        width = 16 + MinecraftClient.getInstance().textRenderer.getWidth(label) + 8;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        boolean hovered = isMouseOver(mouseX, mouseY);
        hover.target(hovered ? 1 : 0);
        select.target(selected ? 1 : 0);
        hover.update(AnimationClock.delta());
        select.update(AnimationClock.delta());
        double h = hover.value();
        double s = select.value();

        if (theme != null) {
            if (s > 0.01) {
                Render2D.roundedRect(context, x, y, width, height, RADIUS,
                    Render2D.argb(theme.navSelected, (int) (255 * s)));
                Render2D.gradientVertical(context, x + 1, y + 3, 2, height - 6,
                    Render2D.argb(theme.accent), Render2D.argb(theme.accentSecondary));
                Render2D.glow(context, x, y, width, height, RADIUS,
                    Render2D.argb(theme.glow, (int) (60 * s)), 3, 2);
            } else if (h > 0.01) {
                Render2D.roundedRect(context, x, y, width, height, RADIUS,
                    Render2D.argb(theme.row, (int) (140 * h)));
            }
        }

        int iconColor = theme == null ? 0xFFFFFFFF
            : Render2D.blend(Render2D.argb(theme.muted), Render2D.argb(theme.accent), Math.max(s, h));
        drawGlyph(context, x + 6, y + height / 2.0, iconColor);

        var tr = MinecraftClient.getInstance().textRenderer;
        int textColor = theme == null ? 0xFFFFFFFF
            : Render2D.blend(Render2D.argb(theme.text), Render2D.argb(theme.accent), s);
        context.drawTextWithShadow(tr, label, (int) Math.round(x + 18),
            (int) Math.round(y + (height - tr.fontHeight) / 2.0), textColor);
    }

    /** Draws a 9x9-ish glyph centred on {@code (cx, cy)} from small rectangles. */
    private void drawGlyph(DrawContext context, double left, double cy, int color) {
        double top = cy - 4.5;
        switch (glyph) {
            case MODULES -> {
                // Four squares in a 2x2 grid.
                Render2D.rect(context, left, top, 4, 4, color);
                Render2D.rect(context, left + 5, top, 4, 4, color);
                Render2D.rect(context, left, top + 5, 4, 4, color);
                Render2D.rect(context, left + 5, top + 5, 4, 4, color);
            }
            case HUD -> {
                // A monitor: an outline with a stand.
                Render2D.roundedOutline(context, left, top, 9, 7, 1, color, 1);
                Render2D.rect(context, left + 3.5, top + 7, 2, 2, color);
            }
            case THEME -> {
                // A paint droplet: a diamond over a dot.
                Render2D.line(context, left + 4.5, top, left + 8.5, top + 4.5, 1.6, color);
                Render2D.line(context, left + 8.5, top + 4.5, left + 4.5, top + 9, 1.6, color);
                Render2D.line(context, left + 4.5, top + 9, left + 0.5, top + 4.5, 1.6, color);
                Render2D.line(context, left + 0.5, top + 4.5, left + 4.5, top, 1.6, color);
            }
            case SETTINGS -> {
                // A cog approximated by a ring and four teeth.
                Render2D.roundedOutline(context, left + 1, top + 1, 7, 7, 3, color, 1.4);
                Render2D.rect(context, left + 3.7, top - 0.5, 1.6, 2, color);
                Render2D.rect(context, left + 3.7, top + 7.5, 1.6, 2, color);
                Render2D.rect(context, left - 0.5, top + 3.7, 2, 1.6, color);
                Render2D.rect(context, left + 7.5, top + 3.7, 2, 1.6, color);
            }
            case INFO -> {
                // A rounded square with a dot and a bar.
                Render2D.roundedOutline(context, left, top, 9, 9, 2, color, 1.2);
                Render2D.rect(context, left + 3.9, top + 1.8, 1.6, 1.6, color);
                Render2D.rect(context, left + 3.9, top + 4.4, 1.6, 3, color);
            }
        }
    }
}
