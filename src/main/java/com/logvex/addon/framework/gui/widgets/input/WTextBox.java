package com.logvex.addon.framework.gui.widgets.input;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.animation.AnimationClock;
import com.logvex.addon.framework.gui.animation.Animator;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A single-line text field.
 *
 * <p>The field only consumes keyboard input while focused, which it becomes on click. The
 * {@link #action} callback fires on every change, so a search screen can filter as the user types.
 * Editing keys (backspace, delete, arrows, home, end) are handled here rather than leaking to the
 * screen. The border eases towards the accent while focused and a blinking caret marks the insertion
 * point.
 */
public class WTextBox extends WWidget {
    public Runnable action;

    private String text;
    private final String placeholder;
    private boolean focused;
    private int cursor;
    private final Animator fillAnim = Animator.of(0);

    public WTextBox(String text, String placeholder) {
        setText(text);
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    public String get() {
        return text;
    }

    public void set(String text) {
        setText(text);
    }

    private void setText(String value) {
        this.text = value == null ? "" : value;
        this.cursor = this.text.length();
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (focused) this.cursor = text.length();
    }

    private void changed() {
        if (action != null) action.run();
    }

    @Override
    public void calculateSize() {
        height = MinecraftClient.getInstance().textRenderer.fontHeight + 8;
        width = Math.max(100, MinecraftClient.getInstance().textRenderer.getWidth(text) + 12);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        double radius = 3;
        boolean hovered = isMouseOver(mouseX, mouseY);
        fillAnim.target(focused ? 1 : 0);
        fillAnim.update(AnimationClock.delta());

        int fill = theme == null ? 0xFF202028 : Render2D.argb(theme.textBoxBackground);
        Render2D.roundedRect(context, x, y, width, height, radius, fill);

        int border = theme == null
            ? (focused ? 0xFF8CC8FF : 0xFF505060)
            : Render2D.blend(
                Render2D.argb(hovered ? theme.textBoxFocused : theme.textBoxBorder),
                Render2D.argb(theme.accent), fillAnim.value());
        Render2D.roundedOutline(context, x, y, width, height, radius, border, focused ? 1.4 : 1);
        if (focused) {
            Render2D.glow(context, x, y, width, height, radius, Render2D.argb(theme == null
                ? new com.logvex.addon.framework.utils.render.color.Color(120, 160, 255) : theme.glow, 70), 3, 2);
        }

        var tr = MinecraftClient.getInstance().textRenderer;
        String shown = text.isEmpty() && !focused ? placeholder : text;
        int color = theme == null ? 0xFFE0E0E0 : Render2D.argb(theme.text);
        if (text.isEmpty() && !focused) color = theme == null ? 0xFF808080 : Render2D.argb(theme.muted, 200);
        context.drawTextWithShadow(tr, shown, (int) x + 5, (int) y + 4, color);

        if (focused) {
            // A caret that blinks on a one-second cycle so it reads as a live cursor.
            if ((AnimationClock.time() % 1.0) < 0.5) {
                int caretX = (int) x + 5 + tr.getWidth(text.substring(0, Math.min(cursor, text.length())));
                Render2D.rect(context, caretX, y + 3, 1, height - 6, Render2D.argb(theme == null
                    ? new com.logvex.addon.framework.utils.render.color.Color(224, 224, 224) : theme.accent));
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        focused = isMouseOver(mouseX, mouseY);
        if (focused) cursor = text.length();
        return focused;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        switch (keyCode) {
            case 259 -> { // backspace
                if (cursor > 0) {
                    text = text.substring(0, cursor - 1) + text.substring(cursor);
                    cursor--;
                    changed();
                }
                return true;
            }
            case 261 -> { // delete
                if (cursor < text.length()) {
                    text = text.substring(0, cursor) + text.substring(cursor + 1);
                    changed();
                }
                return true;
            }
            case 263 -> { // left
                if (cursor > 0) cursor--;
                return true;
            }
            case 262 -> { // right
                if (cursor < text.length()) cursor++;
                return true;
            }
            case 268 -> { // home
                cursor = 0;
                return true;
            }
            case 269 -> { // end
                cursor = text.length();
                return true;
            }
            case 257, 335 -> { // enter
                changed();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused) return false;
        if (chr == '\n' || chr == '\r' || chr == '\t' || Character.isISOControl(chr)) return false;
        text = text.substring(0, cursor) + chr + text.substring(cursor);
        cursor++;
        changed();
        return true;
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return 0xFF000000 | (c.r << 16) | (c.g << 8) | c.b;
    }
}
