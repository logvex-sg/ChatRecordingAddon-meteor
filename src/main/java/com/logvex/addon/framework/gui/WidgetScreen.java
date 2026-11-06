package com.logvex.addon.framework.gui;

import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Base screen that lays widgets out in a vertical list.
 *
 * <p>{@link #initWidgets()} is called on every (re)open and whenever {@link #reload()} runs, so a
 * screen rebuilds its contents rather than mutating widgets in place. {@link #taskAfterRender} lets
 * a background callback ask for a rebuild without touching the screen from another thread.
 */
public abstract class WidgetScreen extends Screen {
    public Runnable taskAfterRender;
    public Screen parent;

    protected final GuiTheme theme;
    protected final WVerticalList root = new WVerticalList();
    protected boolean firstInit = true;

    protected WidgetScreen(GuiTheme theme, String title) {
        super(Text.literal(title == null ? "" : title));
        this.theme = theme;
        this.parent = MinecraftClient.getInstance().currentScreen;
        root.theme = theme;
    }

    public <W extends WWidget> Cell<W> add(W widget) {
        widget.theme = theme;
        return root.add(widget);
    }

    public void clear() {
        root.clear();
    }

    /** Rebuilds the screen contents. Safe to call from a render task. */
    public void reload() {
        root.clear();
        initWidgets();
    }

    public abstract void initWidgets();

    @Override
    protected void init() {
        if (firstInit) {
            firstInit = false;
        }
        reload();
        layoutRoot();
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        layoutRoot();
    }

    protected void layoutRoot() {
        // Cap the list at the screen height so a long page becomes scrollable instead of running off
        // the bottom edge.
        root.maxHeight = Math.max(40, this.height - 20);
        root.calculateSize();
        double maxWidth = Math.min(root.width, this.width - 20);
        root.layout(10, 10, maxWidth);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        root.render(context, mouseX, mouseY, delta);
        runAfterRenderTasks();
    }

    protected void runAfterRenderTasks() {
        if (taskAfterRender != null) {
            Runnable task = taskAfterRender;
            taskAfterRender = null;
            task.run();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return root.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return root.mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return root.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
            || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        root.mouseMoved(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        boolean handled = root.mouseScrolled(mouseX, mouseY, verticalAmount);
        if (handled) layoutRoot();
        return handled || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return root.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return root.charTyped(chr, modifiers) || super.charTyped(chr, modifiers);
    }

    /** Offers a key press to the widget tree; true when a widget consumed it. */
    public boolean handleFocusedKey(int keyCode, int scanCode, int modifiers) {
        return root.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Offers a typed character to the widget tree; true when a widget consumed it. */
    public boolean handleFocusedChar(char chr, int modifiers) {
        return root.charTyped(chr, modifiers);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    public boolean toClipboard() {
        return false;
    }

    public boolean fromClipboard() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
