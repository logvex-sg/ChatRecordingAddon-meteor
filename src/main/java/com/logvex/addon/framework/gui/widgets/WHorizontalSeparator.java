package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A section header, optionally with a rule.
 *
 * <p>With no text it is a plain hairline divider. With text it draws the label in the accent colour
 * followed by a rule that fades out to the right, which is the standard way a client interface
 * separates groups of settings without boxing every one of them.
 */
public class WHorizontalSeparator extends WWidget {
    private final String text;

    public WHorizontalSeparator(String text) {
        this.text = text == null ? "" : text;
    }

    @Override
    public void calculateSize() {
        height = text.isEmpty() ? 5 : MinecraftClient.getInstance().textRenderer.fontHeight + 6;
        width = 0;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        int lineColor = theme == null ? 0xFF606070 : Render2D.argb(theme.separator, 130);
        int accent = theme == null ? 0xFF8CC8FF : Render2D.argb(theme.accent);
        int textColor = theme == null ? 0xFFFFFFFF : Render2D.argb(theme.separatorText);
        int midY = (int) Math.round(y + height / 2.0);

        if (text.isEmpty()) {
            Render2D.gradientHorizontalRounded(context, x, midY, width, 1, 0,
                Render2D.alphaOf(lineColor, 0), lineColor);
            return;
        }

        var tr = MinecraftClient.getInstance().textRenderer;
        int textWidth = tr.getWidth(text);
        // A short accent tick before the label, then the rule fading out after it.
        Render2D.roundedRect(context, x, midY - 2, 2, 5, 1, accent);
        context.drawTextWithShadow(tr, text, (int) Math.round(x + 6), (int) Math.round(y), textColor);
        double ruleStart = x + textWidth + 11;
        if (ruleStart < x + width) {
            Render2D.gradientHorizontalRounded(context, ruleStart, midY, x + width - ruleStart, 1, 0,
                lineColor, Render2D.alphaOf(lineColor, 0));
        }
    }
}
