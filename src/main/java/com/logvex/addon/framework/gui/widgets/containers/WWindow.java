package com.logvex.addon.framework.gui.widgets.containers;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A titled panel whose body stacks widgets vertically.
 *
 * <p>The frame is a rounded, translucent surface with a tinted title bar, matching the look of the
 * rest of the interface. The title carries a small accent dot and a hairline rule runs along the
 * bottom of the bar, which is what makes a window read as a "panel" rather than a floating rectangle.
 */
public class WWindow extends WContainer {
    public final WVerticalList view = new WVerticalList();
    public final String title;

    private static final double TITLE_HEIGHT = 16;
    private static final double RADIUS = 5;

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
        view.layout(this.x + 2, this.y + TITLE_HEIGHT, this.width - 4);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        if (theme == null) {
            context.fill((int) x, (int) y, (int) (x + width), (int) (y + height), 0xCC101014);
            context.drawBorder((int) x, (int) y, (int) width, (int) height, 0xFF606070);
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, this.title,
                (int) x + 4, (int) y + 4, 0xFF8CC8FF);
            view.render(context, mouseX, mouseY, delta);
            return;
        }

        // Body, then the tinted title bar clipped to the top rounded corners.
        Render2D.roundedRect(context, x, y, width, height, RADIUS, Render2D.argb(theme.windowBackground));
        Render2D.roundedRect(context, x, y, width, TITLE_HEIGHT, RADIUS, Render2D.argb(theme.header));
        Render2D.roundedRect(context, x, y + TITLE_HEIGHT - RADIUS, width, RADIUS, 0, Render2D.argb(theme.header));
        Render2D.gradientVertical(context, x + 1, y + 1, width - 2, TITLE_HEIGHT - 1,
            Render2D.argb(theme.accent, 26), Render2D.argb(theme.accent, 0));
        // Hairline under the bar, and the outer border.
        Render2D.rect(context, x + 1, y + TITLE_HEIGHT - 1, width - 2, 1, Render2D.argb(theme.accent, 60));
        Render2D.roundedOutline(context, x, y, width, height, RADIUS, Render2D.argb(theme.windowBorder, 190), 1);

        // Accent dot and title.
        Render2D.roundedRect(context, x + 6, y + 5, 5, 5, 2, Render2D.argb(theme.accent));
        var tr = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(tr, this.title, (int) x + 15, (int) y + 4, Render2D.argb(theme.windowTitle));

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
}
