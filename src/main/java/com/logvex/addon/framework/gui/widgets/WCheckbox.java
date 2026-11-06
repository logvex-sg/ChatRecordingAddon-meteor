package com.logvex.addon.framework.gui.widgets;

import com.logvex.addon.framework.gui.GuiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** A small square toggle. */
public class WCheckbox extends WWidget {
    private final String label;
    private final java.util.function.BooleanSupplier getter;
    private final java.util.function.Consumer<Boolean> setter;

    public WCheckbox(String label, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        this.label = label == null ? "" : label;
        this.getter = getter;
        this.setter = setter;
    }

    @Override
    public void calculateSize() {
        height = 14;
        width = 14 + (label.isEmpty() ? 0 : 4 + MinecraftClient.getInstance().textRenderer.getWidth(label));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) return false;
        setter.accept(!getter.getAsBoolean());
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        int boxSize = 12;
        int top = (int) Math.round(y + (height - boxSize) / 2.0);
        int bg = theme == null ? 0xFF202028 : argb(theme.textBoxBackground);
        int border = theme == null ? 0xFF606070 : argb(theme.textBoxBorder);
        context.fill((int) x, top, (int) x + boxSize, top + boxSize, bg);
        context.drawBorder((int) x, top, boxSize, boxSize, border);

        if (getter.getAsBoolean()) {
            int check = theme == null ? 0xFF6FD08C : argb(theme.textBoxFocused);
            context.fill((int) x + 3, top + 3, (int) x + boxSize - 3, top + boxSize - 3, check);
        }

        if (!label.isEmpty()) {
            var tr = MinecraftClient.getInstance().textRenderer;
            int color = theme == null ? 0xFFFFFFFF : argb(theme.text);
            context.drawTextWithShadow(tr, label, (int) x + boxSize + 4,
                (int) Math.round(y + (height - tr.fontHeight) / 2.0), color);
        }
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return 0xFF000000 | (c.r << 16) | (c.g << 8) | c.b;
    }
}
