package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.gui.animation.Animator;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** A small square toggle with an eased fill. */
public class WCheckbox extends WWidget {
    private static final double BOX = 12;
    private static final double RADIUS = 2;

    private final String label;
    private final java.util.function.BooleanSupplier getter;
    private final java.util.function.Consumer<Boolean> setter;
    private final Animator fill = Animator.of(0);

    public WCheckbox(String label, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        this.label = label == null ? "" : label;
        this.getter = getter;
        this.setter = setter;
    }

    @Override
    public void calculateSize() {
        height = 14;
        width = (float) BOX + (label.isEmpty() ? 0 : 4 + MinecraftClient.getInstance().textRenderer.getWidth(label));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        setter.accept(!getter.getAsBoolean());
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        boolean hovered = isMouseOver(mouseX, mouseY);
        fill.target(getter.getAsBoolean() ? 1 : 0);
        fill.update(AnimationClock.delta());
        double t = fill.value();

        double top = y + (height - BOX) / 2.0;
        int bg = theme == null ? 0xFF202028 : Render2D.argb(theme.textBoxBackground);
        Render2D.roundedRect(context, x, top, BOX, BOX, RADIUS, bg);
        if (t > 0) {
            Render2D.roundedRect(context, x, top, BOX, BOX, RADIUS,
                theme == null ? Render2D.alphaOf(0xFF6FD08C, (int) (255 * t))
                    : Render2D.argb(theme.accent, (int) (255 * t)));
        }
        int border = theme == null ? 0xFF606070
            : Render2D.blend(Render2D.argb(theme.textBoxBorder), Render2D.argb(theme.accent),
                Math.max(t, hovered ? 0.6 : 0));
        Render2D.roundedOutline(context, x, top, BOX, BOX, RADIUS, border, 1);

        // A check mark drawn as two strokes, faded in with the fill.
        if (t > 0.15) {
            int mark = theme == null ? 0xFFFFFFFF : Render2D.argb(theme.windowBackground);
            mark = Render2D.alphaOf(mark, (int) (255 * Math.min(1, (t - 0.15) / 0.6)));
            double cx = x + BOX / 2.0;
            double cy = top + BOX / 2.0;
            Render2D.line(context, cx - 3.0, cy + 0.2, cx - 1.0, cy + 2.2, 1.7, mark);
            Render2D.line(context, cx - 1.0, cy + 2.2, cx + 3.2, cy - 2.2, 1.7, mark);
        }

        if (!label.isEmpty()) {
            var tr = MinecraftClient.getInstance().textRenderer;
            int color = theme == null ? 0xFFFFFFFF : Render2D.argb(theme.text);
            context.drawTextWithShadow(tr, label, (int) (x + BOX + 4),
                (int) Math.round(y + (height - tr.fontHeight) / 2.0), color);
        }
    }
}
