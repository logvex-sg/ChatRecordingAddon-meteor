package com.logvex.addon.framework.gui.widgets.pressable;

import com.logvex.addon.framework.gui.GuiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A labelled on/off switch.
 *
 * <p>It shows its state as a word and a coloured track, so it is obvious at a glance whether a module
 * or HUD element is on, rather than making the user read a checkbox. Clicking anywhere on the row
 * flips it.
 */
public class WToggle extends WPressable {
    private final String label;
    private final java.util.function.BooleanSupplier getter;
    private final java.util.function.Consumer<Boolean> setter;

    private static final double TRACK_WIDTH = 30;
    private static final double TRACK_HEIGHT = 12;

    public WToggle(String label, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        super(null);
        this.label = label == null ? "" : label;
        this.getter = getter;
        this.setter = setter;
    }

    public WToggle(java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        this("", getter, setter);
    }

    @Override
    public void calculateSize() {
        height = Math.max(14, MinecraftClient.getInstance().textRenderer.fontHeight + 4);
        width = TRACK_WIDTH + (label.isEmpty() ? 0 : 6 + MinecraftClient.getInstance().textRenderer.getWidth(label));
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
        boolean on = getter.getAsBoolean();
        boolean hovered = isMouseOver(mouseX, mouseY);

        int trackTop = (int) Math.round(y + (height - TRACK_HEIGHT) / 2.0);
        int trackColor = theme == null
            ? (on ? 0xFF60A5FA : 0xFF303848)
            : argb(on ? theme.toggleOn : (hovered ? theme.rowHover : theme.toggleOff));
        context.fill((int) x, trackTop, (int) (x + TRACK_WIDTH), (int) (trackTop + TRACK_HEIGHT), trackColor);

        // The knob sits on the right when on and the left when off.
        double knobSize = TRACK_HEIGHT - 4;
        double knobX = on ? x + TRACK_WIDTH - knobSize - 2 : x + 2;
        int knobColor = theme == null ? 0xFFFFFFFF : argb(theme.text);
        context.fill((int) Math.round(knobX), trackTop + 2, (int) Math.round(knobX + knobSize), (int) (trackTop + 2 + knobSize), knobColor);

        if (!label.isEmpty()) {
            var tr = MinecraftClient.getInstance().textRenderer;
            int color = theme == null ? 0xFFFFFFFF : argb(hovered ? theme.accent : theme.text);
            context.drawTextWithShadow(tr, label, (int) x + (int) TRACK_WIDTH + 6,
                (int) Math.round(y + (height - tr.fontHeight) / 2.0), color);
        }
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return ((c.a == 0 ? 255 : c.a) << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
