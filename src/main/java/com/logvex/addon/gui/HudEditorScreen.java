package com.logvex.addon.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.layout.HudAnchor;
import com.logvex.addon.framework.systems.hud.layout.HudLayout;
import com.logvex.addon.framework.systems.hud.layout.HudRect;
import com.logvex.addon.framework.utils.render.Render2D;
import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drag-and-drop editor for the HUD.
 *
 * <p>The screen has two halves: a live canvas on the left where every element is drawn at its real
 * position, and a control column on the right. Clicking an element on the canvas selects it; dragging
 * moves it, snapping to a grid when the element asks for it. The control column can then nudge the
 * selection precisely, set its anchor with one click, align it to the screen edge, centre it, or hide
 * it. A search box filters the element list so a large HUD stays manageable.
 *
 * <p>Every change marks the config dirty, so an arrangement survives a restart, and the layout maths
 * lives in {@link HudLayout} so it is unit tested rather than trusted by eye.
 */
public class HudEditorScreen extends WidgetScreen {
    private static final Color OUTLINE = new Color(96, 165, 250, 220);
    private static final Color OUTLINE_SELECTED = new Color(255, 210, 120, 255);

    private HudElement selected;
    private HudElement dragging;
    private double grabX;
    private double grabY;
    private String query = "";
    private boolean showGrid = true;
    private int gridStep = 4;

    /** Width reserved on the right for the control column. */
    private static final int PANEL_WIDTH = 150;

    public HudEditorScreen() {
        this(null);
    }

    public HudEditorScreen(Screen parent) {
        super(GuiThemes.get(), "HUD Editor");
        if (parent != null) this.parent = parent;
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;
        WHorizontalList titleBar = theme.horizontalList();
        titleBar.add(theme.label("HUD Editor").color(theme.accent)).expandX();
        WButton doneTop = titleBar.add(theme.button("Done")).widget();
        doneTop.action = () -> MinecraftClient.getInstance().setScreen(
            parent instanceof ClickGuiScreen ? parent : new ClickGuiScreen(1));
        add(titleBar).expandX();

        add(theme.label("Drag an element on the canvas to move it, or select it and use the controls below.")
            .color(theme.muted)).expandX();
        add(theme.horizontalSeparator()).expandX();

        // Quick actions that apply to the whole HUD.
        WHorizontalList quick = add(theme.horizontalList()).expandX().widget();
        WButton grid = quick.add(theme.button(showGrid ? "Grid: on" : "Grid: off")).expandX().widget();
        grid.action = () -> {
            showGrid = !showGrid;
            reload();
        };
        WButton snapAll = quick.add(theme.button("Snap all")).expandX().widget();
        snapAll.action = () -> {
            for (HudElement element : Hud.get().elements()) snapToEdge(element);
            ConfigManager.get().markDirty();
            reload();
        };
        WButton done = quick.add(theme.button("Done")).expandX().widget();
        done.action = () -> MinecraftClient.getInstance().setScreen(
            parent instanceof ClickGuiScreen ? parent : new ClickGuiScreen(1));

        WTextBox search = add(theme.textBox(query, "Filter elements...")).expandX().widget();
        search.action = () -> {
            query = search.get();
            reload();
        };

        renderSelected(theme);
        renderList(theme);
    }

    // ------------------------------------------------------------------ selected element controls

    private void renderSelected(GuiTheme theme) {
        add(theme.horizontalSeparator(selected == null ? "Selected: none" : "Selected: " + selected.info.title)).expandX();

        if (selected == null) {
            add(theme.label("Click an element on the canvas to select it.").color(theme.muted)).expandX();
            return;
        }

        WTable nudge = add(theme.table()).expandX().widget();
        WButton left = nudge.add(theme.button("\u2190")).widget();
        left.action = () -> move(selected, -1, 0);
        WButton right = nudge.add(theme.button("\u2192")).widget();
        right.action = () -> move(selected, 1, 0);
        WButton up = nudge.add(theme.button("\u2191")).widget();
        up.action = () -> move(selected, 0, -1);
        WButton down = nudge.add(theme.button("\u2193")).widget();
        down.action = () -> move(selected, 0, 1);
        nudge.finish();

        add(theme.horizontalSeparator("Anchor")).expandX();
        WTable anchors = add(theme.table()).expandX().widget();
        for (HudAnchor anchor : HudAnchor.values()) {
            boolean current = selected.anchor() == anchor;
            WButton button = anchors.add(theme.button(current ? "[" + shortAnchor(anchor) + "]" : shortAnchor(anchor))).widget();
            button.action = () -> {
                Hud.get().applyAnchor(selected, anchor);
                ConfigManager.get().markDirty();
                reload();
            };
        }
        anchors.finish();

        add(theme.horizontalSeparator("Align")).expandX();
        WHorizontalList alignRow = add(theme.horizontalList()).expandX().widget();
        WButton toLeft = alignRow.add(theme.button("Left")).expandX().widget();
        toLeft.action = () -> setAbsolute(selected, 4, selected.getY());
        WButton centreX = alignRow.add(theme.button("Centre")).expandX().widget();
        centreX.action = () -> setAbsolute(selected, (this.width - selected.getWidth()) / 2, selected.getY());
        WButton toRight = alignRow.add(theme.button("Right")).expandX().widget();
        toRight.action = () -> setAbsolute(selected, this.width - selected.getWidth() - 4, selected.getY());
        WHorizontalList alignRow2 = add(theme.horizontalList()).expandX().widget();
        WButton toTop = alignRow2.add(theme.button("Top")).expandX().widget();
        toTop.action = () -> setAbsolute(selected, selected.getX(), 4);
        WButton centreY = alignRow2.add(theme.button("Middle")).expandX().widget();
        centreY.action = () -> setAbsolute(selected, selected.getX(), (this.height - selected.getHeight()) / 2);
        WButton toBottom = alignRow2.add(theme.button("Bottom")).expandX().widget();
        toBottom.action = () -> setAbsolute(selected, selected.getX(), this.height - selected.getHeight() - 4);

        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton settings = actions.add(theme.button("Settings")).expandX().widget();
        settings.action = () -> MinecraftClient.getInstance().setScreen(new HudElementSettingsScreen(selected.info, this));
        WButton hide = actions.add(theme.button("Hide")).expandX().widget();
        hide.action = () -> {
            Hud.get().remove(selected);
            selected = null;
            ConfigManager.get().markDirty();
            reload();
        };
    }

    private void renderList(GuiTheme theme) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        add(theme.horizontalSeparator("All elements")).expandX();
        int shown = 0;
        for (HudElementInfo<?> info : Hud.get().infos()) {
            if (!needle.isEmpty() && !matches(info, needle)) continue;
            shown++;
            WTable row = add(theme.table()).expandX().widget();
            boolean active = Hud.get().isActive(info);
            WButton name = row.add(theme.button((active ? "\u25cf " : "\u25cb ") + info.title)).widget();
            name.action = () -> {
                if (!active) {
                    Hud.get().add(info, 4, 4);
                    ConfigManager.get().markDirty();
                }
                selected = Hud.get().instance(info);
                reload();
            };
            row.finish();
        }
        if (shown == 0) add(theme.label("No elements match.").color(theme.muted)).expandX();
    }

    private boolean matches(HudElementInfo<?> info, String needle) {
        if (info.title != null && info.title.toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (info.name != null && info.name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return info.description != null && info.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    // ------------------------------------------------------------------ canvas geometry

    /** The canvas is everything left of the control column. */
    private int canvasWidth() {
        return Math.max(60, this.width - PANEL_WIDTH);
    }

    private void move(HudElement element, int dx, int dy) {
        int x = clampX(element.getX() + dx, element.getWidth());
        int y = clampY(element.getY() + dy, element.getHeight());
        setAbsolute(element, x, y);
    }

    private void setAbsolute(HudElement element, int x, int y) {
        element.setPos(clampX(x, element.getWidth()), clampY(y, element.getHeight()));
        ConfigManager.get().markDirty();
    }

    private void snapToEdge(HudElement element) {
        // Pull an element that is within a few pixels of an edge flush to it.
        int x = element.getX();
        int y = element.getY();
        if (x < 8) x = 4;
        if (y < 8) y = 4;
        if (x + element.getWidth() > canvasWidth() - 8) x = canvasWidth() - element.getWidth() - 4;
        if (y + element.getHeight() > this.height - 8) y = this.height - element.getHeight() - 4;
        element.setPos(x, y);
    }

    private int clampX(int x, int width) {
        return Math.max(0, Math.min(x, Math.max(0, canvasWidth() - width)));
    }

    private int clampY(int y, int height) {
        return Math.max(0, Math.min(y, Math.max(0, this.height - height)));
    }

    private static String shortAnchor(HudAnchor anchor) {
        return switch (anchor) {
            case TOP_LEFT -> "TL";
            case TOP_CENTER -> "TC";
            case TOP_RIGHT -> "TR";
            case MIDDLE_LEFT -> "ML";
            case CENTER -> "C";
            case MIDDLE_RIGHT -> "MR";
            case BOTTOM_LEFT -> "BL";
            case BOTTOM_CENTER -> "BC";
            case BOTTOM_RIGHT -> "BR";
        };
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, Render2D.argb(theme.screenBackground));
        Render2D.roundedRect(context, 2, 2, canvasWidth() - 4, this.height - 4, 5,
            Render2D.argb(theme.windowBackground, 220));

        if (showGrid) {
            int gridColor = Render2D.argb(theme.separator, 22);
            for (int gx = 0; gx < canvasWidth(); gx += gridStep * 8) {
                context.fill(gx, 0, gx + 1, this.height, gridColor);
            }
            for (int gy = 0; gy < this.height; gy += gridStep * 8) {
                context.fill(0, gy, canvasWidth(), gy + 1, gridColor);
            }
        }

        // Draw the elements themselves so the canvas is a real preview rather than empty boxes. The
        // HUD only ticks and draws during the game frame, so the editor has to drive them here.
        HudRenderer renderer = HudRenderer.INSTANCE;
        renderer.begin(context, delta);
        try {
            for (HudElement element : Hud.get().elements()) {
                try {
                    element.reposition(this.width, this.height);
                    element.tick(renderer);
                    element.reposition(this.width, this.height);
                    element.render(renderer);
                } catch (RuntimeException ignored) {
                    // A single element that cannot draw must not take the editor down with it.
                }
            }
        } finally {
            renderer.end();
        }

        // Selection outlines are drawn on top of the elements so the selection is always visible.
        for (HudElement element : Hud.get().elements()) {
            HudRect rect = element.bounds();
            boolean isSelected = element == selected;
            Color outline = isSelected ? OUTLINE_SELECTED : OUTLINE;
            Render2D.roundedOutline(context, rect.x() - 1, rect.y() - 1,
                rect.width() + 2, rect.height() + 2, 3, Render2D.argb(outline), isSelected ? 1.5 : 1);
        }

        // A vertical divider between the canvas and the controls.
        Render2D.rect(context, canvasWidth(), 0, 1, this.height, Render2D.argb(theme.windowBorder, 180));

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || mouseX > canvasWidth()) return false;

        List<HudElement> elements = Hud.get().elements();
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            HudRect rect = element.bounds();
            if (rect.contains((int) mouseX, (int) mouseY)) {
                dragging = element;
                selected = element;
                element.setDragging(true);
                grabX = mouseX - rect.x();
                grabY = mouseY - rect.y();
                reload();
                return true;
            }
        }
        selected = null;
        reload();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging != null) {
            int x = (int) Math.round(mouseX - grabX);
            int y = (int) Math.round(mouseY - grabY);
            if (dragging.settings.get("snap-to-grid") != null
                && Boolean.TRUE.equals(dragging.settings.get("snap-to-grid").get())) {
                x = HudLayout.snap(x, gridStep);
                y = HudLayout.snap(y, gridStep);
            }
            dragging.setPos(clampX(x, dragging.getWidth()), clampY(y, dragging.getHeight()));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != null) {
            dragging.setDragging(false);
            dragging = null;
            ConfigManager.get().markDirty();
            reload();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX <= canvasWidth() && selected != null) {
            // Scroll over the canvas nudges the selection vertically; a quick way to fine-tune.
            move(selected, 0, (int) -Math.signum(verticalAmount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** The control column is laid out to the right of the canvas. */
    @Override
    protected void layoutRoot() {
        root.maxHeight = Math.max(40, this.height - 20);
        root.calculateSize();
        double x = canvasWidth() + 6;
        double maxWidth = Math.max(80, this.width - x - 6);
        root.layout(x, 10, maxWidth);
    }
}
