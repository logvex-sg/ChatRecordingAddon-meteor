package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.gui.animation.Animator;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * A horizontal slider over a numeric range.
 *
 * <p>The track is rounded and the filled portion is a gradient in the accent colour with a soft glow
 * under the pointer, so the current value is readable at a glance. The value label is drawn centred
 * on the track and switches to a light colour once the fill passes under it, which keeps it legible
 * whether the slider is near its minimum or its maximum.
 */
public class WSlider extends WWidget {
    private final double min;
    private final double max;
    private final boolean integer;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private boolean dragging;

    private final Animator hover = Animator.of(0);

    public WSlider(double min, double max, boolean integer, DoubleSupplier getter, DoubleConsumer setter) {
        this.min = min;
        this.max = max;
        this.integer = integer;
        this.getter = getter;
        this.setter = setter;
    }

    public String displayValue() {
        double value = getter.getAsDouble();
        return integer ? String.valueOf((long) value) : String.format("%.2f", value);
    }

    @Override
    public void calculateSize() {
        width = 100;
        height = MinecraftClient.getInstance().textRenderer.fontHeight + 8;
    }

    private void apply(double mouseX) {
        double fraction = width <= 0 ? 0 : (mouseX - x) / width;
        fraction = Math.max(0, Math.min(1, fraction));
        double value = min + fraction * (max - min);
        if (integer) value = Math.round(value);
        setter.accept(value);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        dragging = true;
        apply(mouseX);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!dragging) return false;
        apply(mouseX);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasDragging = dragging;
        dragging = false;
        return wasDragging;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        double value = getter.getAsDouble();
        double fraction = max > min ? (value - min) / (max - min) : 0;
        fraction = Math.max(0, Math.min(1, fraction));

        boolean hovered = isMouseOver(mouseX, mouseY) || dragging;
        hover.target(hovered ? 1 : 0);
        hover.update(AnimationClock.delta());
        double h = hover.value();

        double radius = height / 2.0;
        int trackColor = theme == null ? 0xFF202028 : Render2D.argb(theme.textBoxBackground);
        Render2D.roundedRect(context, x, y, width, height, radius, trackColor);

        double filled = width * fraction;
        if (filled > 0) {
            int left = theme == null ? 0xFF4C6EA0 : Render2D.argb(theme.accent);
            int right = theme == null ? 0xFF6FA8FF : Render2D.argb(theme.accentSecondary);
            Render2D.gradientHorizontalRounded(context, x, y, filled, height, radius, left, right);
            if (h > 0.01) {
                Render2D.glow(context, x, y, filled, height, radius,
                    Render2D.argb(theme == null ? new com.logvex.addon.framework.utils.render.color.Color(120, 160, 255) : theme.glow,
                        (int) (80 * h)), 3, 2);
            }
        }

        int border = theme == null ? 0xFF505060
            : Render2D.blend(Render2D.argb(theme.textBoxBorder), Render2D.argb(theme.accent), h);
        Render2D.roundedOutline(context, x, y, width, height, radius, border, 1);

        var tr = MinecraftClient.getInstance().textRenderer;
        String label = displayValue();
        int tx = (int) Math.round(x + (width - tr.getWidth(label)) / 2.0);
        int ty = (int) Math.round(y + (height - tr.fontHeight) / 2.0);
        // Where the label sits over the bright accent fill it is drawn dark, so it stays legible at
        // both ends of the range instead of washing out against the gradient.
        boolean overFill = tx + tr.getWidth(label) / 2.0 <= x + filled;
        int textColor = theme == null
            ? (overFill ? 0xFF10131A : 0xFFFFFFFF)
            : (overFill ? Render2D.argb(theme.windowBackground, 235) : Render2D.argb(theme.text));
        context.drawTextWithShadow(tr, label, tx, ty, textColor);
    }
}
