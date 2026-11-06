package com.logvex.addon.framework.systems.hud;

import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * Drawing helpers shared by HUD elements.
 *
 * <p>Text is drawn through the client text renderer, scaled with a matrix push/pop so a scaled
 * element never disturbs the rest of the frame. A negative scale means "use the HUD's global text
 * scale", which is how an element with its custom scale disabled behaves.
 */
public class HudRenderer {
    public static final HudRenderer INSTANCE = new HudRenderer();

    public DrawContext drawContext;
    public double delta;

    public void begin(DrawContext drawContext, double delta) {
        this.drawContext = drawContext;
        this.delta = delta;
    }

    public void end() {
        this.drawContext = null;
    }

    private static double resolveScale(double scale) {
        if (scale > 0) return scale;
        return Hud.get().textScale();
    }

    public void quad(double x, double y, double width, double height, Color color) {
        if (drawContext == null || width <= 0 || height <= 0) return;
        drawContext.fill((int) x, (int) y, (int) (x + width), (int) (y + height), argb(color, 255));
    }

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
