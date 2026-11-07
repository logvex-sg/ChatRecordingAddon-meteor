package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.utils.render.Render2D;
import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** A single line of text. */
public class WLabel extends WWidget {
    private String text;
    private Color color;

    public WLabel(String text, Color color) {
        this.text = text == null ? "" : text;
        this.color = color;
    }

    public WLabel color(Color color) {
        this.color = color;
        return this;
    }

    public String get() {
        return text;
    }

    public void set(String text) {
        this.text = text == null ? "" : text;
    }

    public String text() {
        return text;
    }

    public Color color() {
        return color;
    }

    @Override
    public void calculateSize() {
        var tr = MinecraftClient.getInstance().textRenderer;
        width = tr.getWidth(text);
        height = tr.fontHeight;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        var tr = MinecraftClient.getInstance().textRenderer;
        int argb = Render2D.argb(color);
        context.drawTextWithShadow(tr, text, (int) Math.round(x), (int) Math.round(y), argb);
    }
}
