package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** A horizontal rule, optionally with a label centred on it. */
public class WHorizontalSeparator extends WWidget {
    private final String text;

    public WHorizontalSeparator(String text) {
        this.text = text == null ? "" : text;
    }

    @Override
    public void calculateSize() {
        height = text.isEmpty() ? 3 : MinecraftClient.getInstance().textRenderer.fontHeight + 2;
        width = 0;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        int lineColor = theme == null ? 0xFF606070 : argb(theme.separator);
        int textColor = theme == null ? 0xFFFFFFFF : argb(theme.separatorText);
        int midY = (int) Math.round(y + height / 2.0);

        if (text.isEmpty()) {
            context.fill((int) x, midY, (int) (x + width), midY + 1, lineColor);
            return;
        }

        var tr = MinecraftClient.getInstance().textRenderer;
        int textWidth = tr.getWidth(text);
        context.fill((int) x, midY, (int) x + 2, midY + 1, lineColor);
        context.drawTextWithShadow(tr, text, (int) x + 5, (int) Math.round(y), textColor);
        context.fill((int) x + textWidth + 9, midY, (int) (x + width), midY + 1, lineColor);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return 0xFF000000 | (c.r << 16) | (c.g << 8) | c.b;
    }
}
