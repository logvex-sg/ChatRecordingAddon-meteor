package com.logvex.addon.framework.gui.widgets.pressable;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.gui.animation.Animator;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A labelled on/off switch.
 *
 * <p>It shows its state as a word and a coloured track, so it is obvious at a glance whether a module
 * or HUD element is on, rather than making the user read a checkbox. Clicking anywhere on the row
 * flips it. The knob slides and the track crossfades on an eased curve, and the "On" state carries a
 * faint glow so a glance down a long list finds the enabled entries immediately.
 */
public class WToggle extends WPressable {
    private final String label;
    private final java.util.function.BooleanSupplier getter;
    private final java.util.function.Consumer<Boolean> setter;

    private static final double TRACK_WIDTH = 28;
    private static final double TRACK_HEIGHT = 12;

    private final Animator slide = Animator.of(0);

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
        slide.target(on ? 1 : 0);
        slide.update(AnimationClock.delta());
        double t = slide.value();

        double trackTop = y + (height - TRACK_HEIGHT) / 2.0;
        if (theme == null) {
            Render2D.roundedRect(context, x, trackTop, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2,
                Render2D.blend(0xFF303848, 0xFF60A5FA, t));
        } else {
            int track = Render2D.blend(
                Render2D.argb(hovered ? theme.rowHover : theme.toggleOff),
                Render2D.argb(theme.toggleOn), t);
            Render2D.roundedRect(context, x, trackTop, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2, track);
            if (t > 0.01) {
                Render2D.glow(context, x, trackTop, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2,
                    Render2D.argb(theme.glow, (int) (90 * t)), 3, 1.5);
            }
        }

        // The knob slides between the two ends as the animation advances.
        double knobSize = TRACK_HEIGHT - 4;
        double travel = TRACK_WIDTH - knobSize - 4;
        double knobX = x + 2 + travel * t;
        int knobColor = theme == null ? 0xFFFFFFFF : Render2D.argb(theme.text);
        Render2D.roundedRect(context, knobX, trackTop + 2, knobSize, knobSize, knobSize / 2, knobColor);

        if (!label.isEmpty()) {
            var tr = MinecraftClient.getInstance().textRenderer;
            int color = theme == null ? 0xFFFFFFFF : Render2D.argb(hovered ? theme.accent : theme.text);
            context.drawTextWithShadow(tr, label, (int) Math.round(x + TRACK_WIDTH + 6),
                (int) Math.round(y + (height - tr.fontHeight) / 2.0), color);
        }
    }
}
