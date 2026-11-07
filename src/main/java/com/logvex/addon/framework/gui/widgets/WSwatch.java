package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.utils.render.Render2D;
import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A filled colour block, optionally captioned.
 *
 * <p>Used by the appearance page to show the active accent, secondary and status colours side by
 * side, so a preset can be judged without leaving the window. The caption is drawn underneath in the
 * theme's muted colour when the block is tall enough to hold it.
 */
public class WSwatch extends WWidget {
    private static final double RADIUS = 3;

    private final Color color;
    private final String caption;

    public WSwatch(Color color, String caption) {
        this.color = color;
        this.caption = caption == null ? "" : caption;
    }

    @Override
    public void calculateSize() {
        width = 26;
        height = 22 + (caption.isEmpty() ? 0 : MinecraftClient.getInstance().textRenderer.fontHeight + 2);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        boolean hovered = isMouseOver(mouseX, mouseY);
        Render2D.roundedRect(context, x, y, width, 22, RADIUS, Render2D.argb(color));
        if (hovered) {
            Render2D.glow(context, x, y, width, 22, RADIUS, Render2D.argb(color, 120), 4, 3);
            Render2D.roundedOutline(context, x, y, width, 22, RADIUS, Render2D.argb(theme == null
                ? new Color(255, 255, 255) : theme.text), 1.4);
        }
        if (!caption.isEmpty()) {
            var tr = MinecraftClient.getInstance().textRenderer;
            int color = theme == null ? 0xFFFFFFFF : Render2D.argb(theme.muted);
            context.drawTextWithShadow(tr, caption, (int) Math.round(x), (int) Math.round(y + 24), color);
        }
    }
}
