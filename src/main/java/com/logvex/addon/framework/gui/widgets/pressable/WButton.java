package com.logvex.addon.framework.gui.widgets.pressable;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.gui.animation.Animator;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A labelled button.
 *
 * <p>Buttons are flat, rounded and animated: the fill lightens and the border brightens as the
 * pointer arrives, eased over a fraction of a second so the interface feels responsive without
 * flickering. A {@linkplain #primary primary} button draws in the accent colour and carries a soft
 * glow, which is how the one action that matters on a page is distinguished from the rest.
 */
public class WButton extends WPressable {
    private static final double RADIUS = 3;

    private String text;
    private boolean primary;
    private final Animator hover = Animator.of(0);

    public WButton(String text, Runnable action) {
        super(action);
        this.text = text == null ? "" : text;
    }

    public String get() {
        return text;
    }

    public void set(String text) {
        this.text = text == null ? "" : text;
    }

    /** Marks this button as the primary action on its page, drawing it in the accent colour. */
    public WButton primary() {
        this.primary = true;
        return this;
    }

    public boolean isPrimary() {
        return primary;
    }

    @Override
    public void calculateSize() {
        var tr = MinecraftClient.getInstance().textRenderer;
        width = tr.getWidth(text) + 14;
        height = tr.fontHeight + 7;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        boolean hovered = isMouseOver(mouseX, mouseY);
        hover.target(hovered ? 1 : 0);
        hover.update(AnimationClock.delta());
        double t = hover.value();

        int fill;
        int textColor;
        if (theme == null) {
            fill = hovered ? 0xFF3A3A4E : 0xFF303040;
            textColor = 0xFFFFFFFF;
        } else if (primary) {
            fill = Render2D.blend(Render2D.argb(theme.accent), Render2D.argb(theme.accentSecondary), t);
            textColor = Render2D.argb(theme.windowBackground.r, theme.windowBackground.g, theme.windowBackground.b, 255);
            Render2D.glow(context, x, y, width, height, RADIUS, Render2D.argb(theme.glow), 4, 2.5);
        } else {
            fill = Render2D.blend(Render2D.argb(theme.button), Render2D.argb(theme.buttonHover), t);
            textColor = Render2D.blend(Render2D.argb(theme.text), Render2D.argb(theme.accent), t);
        }

        Render2D.roundedRect(context, x, y, width, height, RADIUS, fill);
        if (theme != null) {
            int border = primary
                ? Render2D.argb(theme.accentSecondary, 200)
                : Render2D.blend(Render2D.argb(theme.separator), Render2D.argb(theme.accent), t * 0.8);
            Render2D.roundedOutline(context, x, y, width, height, RADIUS, border, 1);
        }

        var tr = MinecraftClient.getInstance().textRenderer;
        int tx = (int) Math.round(x + (width - tr.getWidth(text)) / 2.0);
        int ty = (int) Math.round(y + (height - tr.fontHeight) / 2.0);
        context.drawTextWithShadow(tr, text, tx, ty, textColor);
    }
}
