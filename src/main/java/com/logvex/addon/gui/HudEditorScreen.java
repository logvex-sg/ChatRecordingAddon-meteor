package com.logvex.addon.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Drag-and-drop editor for the HUD.
 *
 * <p>Shows every active element at its real position and lets the user move it with the mouse, which
 * is the only practical way to arrange overlapping elements. The list below the canvas nudges an
 * element precisely when dragging is too coarse, and every change marks the config dirty so the
 * position survives a restart.
 */
public class HudEditorScreen extends WidgetScreen {
    private static final Color OUTLINE = new Color(140, 200, 255, 200);

    private HudElement dragging;
    private double grabX;
    private double grabY;

    public HudEditorScreen() {
        super(GuiThemes.get(), "HUD Editor");
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        add(theme.label("Drag an element to move it. Use the arrows for precise placement.")
            .color(theme.muted)).expandX();
        add(theme.horizontalSeparator()).expandX();

        List<HudElement> elements = Hud.get().allInstances();
        if (elements.isEmpty()) {
            add(theme.label("No HUD elements have been created yet. Enable one in the HUD tab.")
                .color(theme.muted)).expandX();
        } else {
            for (HudElement element : elements) {
                if (!element.isActive()) continue;
                add(row(theme, element)).expandX();
            }
        }

        add(theme.horizontalSeparator()).expandX();
        add(theme.button("Back", () -> net.minecraft.client.MinecraftClient.getInstance()
            .setScreen(new ClickGuiScreen()))).expandX();
    }

    private WTable row(GuiTheme theme, HudElement element) {
        WTable table = theme.table();

        table.add(theme.label(element.info.title)).expandCellX();

        WButton left = table.add(theme.button("\u2190")).widget();
        left.action = () -> move(element, -2, 0);
        WButton right = table.add(theme.button("\u2192")).widget();
        right.action = () -> move(element, 2, 0);
        WButton up = table.add(theme.button("\u2191")).widget();
        up.action = () -> move(element, 0, -2);
        WButton down = table.add(theme.button("\u2193")).widget();
        down.action = () -> move(element, 0, 2);

        WButton hide = table.add(theme.button("Hide")).widget();
        hide.action = () -> {
            Hud.get().remove(element);
            ConfigManager.get().markDirty();
            reload();
        };

        table.finish();
        return table;
    }

    private void move(HudElement element, int dx, int dy) {
        element.setPos(Math.max(0, element.getX() + dx), Math.max(0, element.getY() + dy));
        ConfigManager.get().markDirty();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, argb(theme.screenBackground));
        // Draw the live elements first so they sit behind the control list, then the widgets on top.
        for (HudElement element : Hud.get().elements()) {
            int x = element.getX();
            int y = element.getY();
            int w = Math.max(8, element.getWidth());
            int h = Math.max(8, element.getHeight());
            context.fill(x - 1, y - 1, x + w + 1, y + h + 1, argb(OUTLINE));
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;

        // Topmost element under the cursor wins, so overlapping elements are still reachable.
        List<HudElement> elements = Hud.get().elements();
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            if (mouseX >= element.getX() && mouseX <= element.getX() + Math.max(8, element.getWidth())
                && mouseY >= element.getY() && mouseY <= element.getY() + Math.max(8, element.getHeight())) {
                dragging = element;
                grabX = mouseX - element.getX();
                grabY = mouseY - element.getY();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging != null) {
            dragging.setPos(
                Math.max(0, (int) (mouseX - grabX)),
                Math.max(0, (int) (mouseY - grabY)));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null) {
            dragging = null;
            ConfigManager.get().markDirty();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static int argb(Color c) {
        int a = c.a == 0 ? 255 : c.a;
        return (a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
