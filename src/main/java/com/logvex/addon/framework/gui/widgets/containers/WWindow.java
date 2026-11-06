package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** A titled panel whose body stacks widgets vertically. */
public class WWindow extends WContainer {
    public final WVerticalList view = new WVerticalList();
    public final String title;

    private static final double TITLE_HEIGHT = 14;

    public WWindow(String title) {
        this.title = title == null ? "" : title;
    }

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        Cell<W> cell = view.add(widget);
        cell.widget.theme = theme;
        return cell;
    }

    @Override
    public void clear() {
        view.clear();
    }

    @Override
    public boolean isEmpty() {
        return view.isEmpty();
    }

    @Override
    public void calculateSize() {
        view.calculateSize();
        width = Math.max(view.width, 200);
        height = view.height + TITLE_HEIGHT;
    }

    @Override
    public void layout(double x, double y, double width) {
        super.layout(x, y, width);
        view.layout(this.x, this.y + TITLE_HEIGHT, this.width);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        int bg = theme == null ? 0xCC101014 : argb(theme.windowBackground);
        int border = theme == null ? 0xFF606070 : argb(theme.windowBorder);
        int titleColor = theme == null ? 0xFF8CC8FF : argb(theme.windowTitle);

        context.fill((int) x, (int) y, (int) (x + width), (int) (y + height), bg);
        context.drawBorder((int) x, (int) y, (int) width, (int) height, border);

        var tr = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(tr, this.title, (int) x + 4, (int) y + 3, titleColor);

        view.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return view.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return view.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return view.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return view.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return view.charTyped(chr, modifiers);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return 0xFF000000 | (c.r << 16) | (c.g << 8) | c.b;
    }
}
