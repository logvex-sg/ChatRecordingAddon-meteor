package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.utils.render.Render2D;
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
 * rounded box, {@link #outline} a rounded border, {@link #glow} a soft halo and {@link #bar} a
 * progress bar. They delegate to {@link Render2D}, so a HUD panel and a GUI button round their
 * corners with the same code and the two surfaces cannot drift apart. Keeping them here means an
 * element only decides what to show, not how a box is drawn.
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
        // Advance the shared animation clock once per frame so HUD animations run on the same time
        // base as the GUI's.
        AnimationClock.tick();
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
        Render2D.rect(drawContext, x, y, width, height, Render2D.argb(color, 255));
    }

    /**
     * A filled rounded panel. The corner radius is clamped to half the shorter side so a thin element
     * still draws.
     */
    public void panel(double x, double y, double width, double height, Color color, int radius) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        int argb = Render2D.argb(color, color == null ? 255 : (color.a == 0 ? 255 : color.a));
        Render2D.roundedRect(drawContext, x, y, width, height, radius, argb);
    }

    /** A one-pixel rounded outline, drawn inside the given bounds. */
    public void outline(double x, double y, double width, double height, Color color, int radius) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        int argb = Render2D.argb(color, color == null ? 255 : (color.a == 0 ? 255 : color.a));
        Render2D.roundedOutline(drawContext, x, y, width, height, radius, argb, 1);
    }

    /** A soft halo around a panel, drawn before the panel itself. */
    public void glow(double x, double y, double width, double height, Color color, int radius) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        Render2D.glow(drawContext, x, y, width, height, radius,
            Render2D.argb(color, color == null ? 120 : (color.a == 0 ? 120 : color.a)), 4, 3);
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

    /**
     * A progress bar whose filled portion runs through a gradient from the accent to its secondary
     * colour, which is what makes a HUD bar read as "lit" rather than flat.
     */
    public void gradientBar(double x, double y, double width, double height, double fraction,
                            Color from, Color to, Color track) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        double radius = Math.max(0, Math.min(height / 2, 2));
        Render2D.roundedRect(drawContext, x, y, width, height, radius, Render2D.argb(track, 200));
        double clamped = Math.max(0, Math.min(1, fraction));
        double filled = width * clamped;
        if (filled > 0) {
            Render2D.gradientHorizontalRounded(drawContext, x, y, filled, height, radius,
                Render2D.argb(from), Render2D.argb(to));
        }
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
