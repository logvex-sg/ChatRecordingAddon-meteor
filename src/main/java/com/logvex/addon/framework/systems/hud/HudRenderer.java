package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * Drawing helpers shared by HUD elements.
 *
 * <p>Text is drawn through the client text renderer, scaled with a matrix push/pop so a scaled element
 * never disturbs the rest of the frame. A negative scale means "use the HUD's global text scale",
 * which is how an element with its custom scale disabled behaves.
 *
 * <p>The panel helpers give every element the same visual language: {@link #panel} draws a filled
 * rounded box, {@link #outline} a rounded border and {@link #bar} a progress bar. Keeping them here
 * means an element only decides what to show, not how a box is drawn, so the whole HUD looks like one
 * product rather than a set of unrelated rectangles.
 */
public class HudRenderer {
    public static final HudRenderer INSTANCE = new HudRenderer();

    public DrawContext drawContext;
    public double delta;
    /** The active palette, refreshed once per frame so every element shares it. */
    public HudThemeColors theme = HudThemeColors.current();

    public void begin(DrawContext drawContext, double delta) {
        this.drawContext = drawContext;
        this.delta = delta;
        this.theme = HudThemeColors.current();
    }

    public void end() {
        this.drawContext = null;
    }

    private static double resolveScale(double scale) {
        if (scale > 0) return scale;
        return Hud.get().textScale();
    }

    // ------------------------------------------------------------------ shapes

    public void quad(double x, double y, double width, double height, Color color) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        drawContext.fill((int) x, (int) y, (int) Math.ceil(x + width), (int) Math.ceil(y + height), argb(color, 255));
    }

    /**
     * A filled rounded panel. The corner radius is clamped to half the shorter side so a thin element
     * still draws. Only the four corner pixels are inset, which is cheap and reads as "rounded".
     */
    public void panel(double x, double y, double width, double height, Color color, int radius) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        int argb = argb(color, color == null ? 255 : (color.a == 0 ? 255 : color.a));
        int xi = (int) x;
        int yi = (int) y;
        int wi = (int) Math.ceil(width);
        int hi = (int) Math.ceil(height);
        int r = Math.max(0, Math.min(radius, Math.min(wi, hi) / 2));

        if (r == 0) {
            drawContext.fill(xi, yi, xi + wi, yi + hi, argb);
            return;
        }
        // Body without the corner squares, then the four rounded corners.
        drawContext.fill(xi + r, yi, xi + wi - r, yi + hi, argb);
        drawContext.fill(xi, yi + r, xi + r, yi + hi - r, argb);
        drawContext.fill(xi + wi - r, yi + r, xi + wi, yi + hi - r, argb);
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt(r * r - (r - i) * (r - i)) + 0.5);
            drawContext.fill(xi + inset, yi + i, xi + r, yi + i + 1, argb);
            drawContext.fill(xi + wi - r, yi + i, xi + wi - inset, yi + i + 1, argb);
            drawContext.fill(xi + inset, yi + hi - i - 1, xi + r, yi + hi - i, argb);
            drawContext.fill(xi + wi - r, yi + hi - i - 1, xi + wi - inset, yi + hi - i, argb);
        }
    }

    /** A one-pixel rounded outline, drawn inside the given bounds. */
    public void outline(double x, double y, double width, double height, Color color, int radius) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        int argb = argb(color, color == null ? 255 : (color.a == 0 ? 255 : color.a));
        int xi = (int) x;
        int yi = (int) y;
        int wi = (int) Math.ceil(width);
        int hi = (int) Math.ceil(height);
        int r = Math.max(0, Math.min(radius, Math.min(wi, hi) / 2));

        drawContext.fill(xi + r, yi, xi + wi - r, yi + 1, argb);
        drawContext.fill(xi + r, yi + hi - 1, xi + wi - r, yi + hi, argb);
        drawContext.fill(xi, yi + r, xi + 1, yi + hi - r, argb);
        drawContext.fill(xi + wi - 1, yi + r, xi + wi, yi + hi - r, argb);
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt(r * r - (r - i) * (r - i)) + 0.5);
            drawContext.fill(xi + inset, yi + i, xi + r, yi + i + 1, argb);
            drawContext.fill(xi + wi - r, yi + i, xi + wi - inset, yi + i + 1, argb);
            drawContext.fill(xi + inset, yi + hi - i - 1, xi + r, yi + hi - i, argb);
            drawContext.fill(xi + wi - r, yi + hi - i - 1, xi + wi - inset, yi + hi - i, argb);
        }
    }

    /**
     * A progress bar: a track with a filled portion. {@code fraction} is clamped to 0..1 so a bad
     * value can never draw outside the bar.
     */
    public void bar(double x, double y, double width, double height, double fraction, Color fill, Color track) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        int radius = (int) Math.max(0, Math.min(height / 2, 2));
        panel(x, y, width, height, track, radius);
        double clamped = Math.max(0, Math.min(1, fraction));
        double filled = width * clamped;
        if (filled > 0) panel(x, y, filled, height, fill, radius);
    }

    // ------------------------------------------------------------------ text

    public double text(String text, double x, double y, Color color, boolean shadow) {
        return text(text, x, y, color, shadow, -1);
    }

    public double text(String text, double x, double y, Color color, boolean shadow, double scale) {
        if (drawContext == null || text == null) return 0;
        double s = resolveScale(scale);
        var tr = MinecraftClient.getInstance().textRenderer;
        var matrices = drawContext.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.scale((float) s, (float) s, 1f);
        drawContext.drawText(tr, text, 0, 0, argb(color, 255), shadow);
        matrices.pop();
        return tr.getWidth(text) * s;
    }

    /** Draws text right-aligned so it ends at {@code right}. */
    public void textRight(String text, double right, double y, Color color, boolean shadow, double scale) {
        double width = textWidth(text, shadow, scale);
        text(text, right - width, y, color, shadow, scale);
    }

    public double textWidth(String text, boolean shadow) {
        return textWidth(text, shadow, -1);
    }

    public double textWidth(String text, double scale) {
        return textWidth(text, true, scale);
    }

    public double textWidth(String text, boolean shadow, double scale) {
        if (text == null) return 0;
        return MinecraftClient.getInstance().textRenderer.getWidth(text) * resolveScale(scale);
    }

    public double textHeight() {
        return textHeight(true, -1);
    }

    public double textHeight(boolean shadow) {
        return textHeight(shadow, -1);
    }

    public double textHeight(boolean shadow, double scale) {
        return MinecraftClient.getInstance().textRenderer.fontHeight * resolveScale(scale);
    }

    public void item(ItemStack stack, int x, int y, float scale, boolean overlay) {
        if (drawContext == null || stack == null || stack.isEmpty()) return;
        var matrices = drawContext.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.scale(scale, scale, 1f);
        drawContext.drawItem(stack, 0, 0);
        if (overlay) drawContext.drawItemInSlot(MinecraftClient.getInstance().textRenderer, stack, 0, 0);
        matrices.pop();
    }

    static int argb(Color color, int alpha) {
        if (color == null) return 0xFFFFFFFF;
        int a = color.a == 0 ? alpha : color.a;
        return (a << 24) | (color.r << 16) | (color.g << 8) | color.b;
    }
}
