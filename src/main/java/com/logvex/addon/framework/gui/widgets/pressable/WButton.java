package com.logvex.addon.framework.gui.widgets.pressable;

import com.logvex.addon.framework.gui.GuiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** A labelled button. */
public class WButton extends WPressable {
    private String text;

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

    @Override
    public void calculateSize() {
        var tr = MinecraftClient.getInstance().textRenderer;
        width = tr.getWidth(text) + 12;
        height = tr.fontHeight + 6;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        boolean hovered = isMouseOver(mouseX, mouseY);
        int fill = theme == null
            ? 0xFF303040
            : argb(hovered ? theme.buttonHover : theme.button);
        context.fill((int) x, (int) y, (int) (x + width), (int) (y + height), fill);

        var tr = MinecraftClient.getInstance().textRenderer;
        int textColor = theme == null ? 0xFFFFFFFF : argb(theme.text);
        int tx = (int) Math.round(x + (width - tr.getWidth(text)) / 2.0);
        int ty = (int) Math.round(y + (height - tr.fontHeight) / 2.0);
        context.drawTextWithShadow(tr, text, tx, ty, textColor);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return 0xFF000000 | (c.r << 16) | (c.g << 8) | c.b;
    }
}
