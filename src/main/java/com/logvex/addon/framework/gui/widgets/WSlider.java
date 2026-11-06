package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** A horizontal slider over a numeric range. */
public class WSlider extends WWidget {
    private final double min;
    private final double max;
    private final boolean integer;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private boolean dragging;

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
        height = MinecraftClient.getInstance().textRenderer.fontHeight + 6;
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

        int bg = theme == null ? 0xFF202028 : argb(theme.textBoxBackground);
        int fill = theme == null ? 0xFF4C6EA0 : argb(theme.textBoxFocused);
        int border = theme == null ? 0xFF505060 : argb(theme.textBoxBorder);

        context.fill((int) x, (int) y, (int) (x + width), (int) (y + height), bg);
        context.fill((int) x, (int) y, (int) (x + width * fraction), (int) (y + height), fill);
        context.drawBorder((int) x, (int) y, (int) width, (int) height, border);

        var tr = MinecraftClient.getInstance().textRenderer;
        String label = displayValue();
        int color = theme == null ? 0xFFFFFFFF : argb(theme.text);
        context.drawTextWithShadow(tr, label,
            (int) Math.round(x + (width - tr.getWidth(label)) / 2.0),
            (int) Math.round(y + (height - tr.fontHeight) / 2.0), color);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return 0xFF000000 | (c.r << 16) | (c.g << 8) | c.b;
    }
}
