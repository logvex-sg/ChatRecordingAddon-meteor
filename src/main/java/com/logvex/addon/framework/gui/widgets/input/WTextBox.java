package com.logvex.addon.framework.gui.widgets.input;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Formatting;

/**
 * A single-line text field.
 *
 * <p>The field only consumes keyboard input while focused, which it becomes on click. The
 * {@link #action} callback fires on every change, so a search screen can filter as the user types.
 * Editing keys (backspace, delete, arrows, home, end) are handled here rather than leaking to the
 * screen.
 */
public class WTextBox extends WWidget {
    public Runnable action;

    private String text;
    private final String placeholder;
    private boolean focused;
    private int cursor;

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
        height = MinecraftClient.getInstance().textRenderer.fontHeight + 6;
        width = Math.max(100, MinecraftClient.getInstance().textRenderer.getWidth(text) + 10);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        GuiTheme theme = this.theme;
        int fill = theme == null ? 0xFF202028 : argb(theme.textBoxBackground);
        int border = theme == null ? 0xFF505060 : argb(focused ? theme.textBoxFocused : theme.textBoxBorder);
        context.fill((int) x, (int) y, (int) (x + width), (int) (y + height), fill);
        context.drawBorder((int) x, (int) y, (int) width, (int) height, border);

        var tr = MinecraftClient.getInstance().textRenderer;
        String shown = text.isEmpty() && !focused ? placeholder : text;
        int color = theme == null ? 0xFFE0E0E0 : argb(theme.text);
        if (text.isEmpty() && !focused) color = 0xFF808080;
        context.drawTextWithShadow(tr, shown, (int) x + 4, (int) y + 3, color);

        if (focused) {
            int caretX = (int) x + 4 + tr.getWidth(text.substring(0, Math.min(cursor, text.length())));
            context.fill(caretX, (int) y + 2, caretX + 1, (int) (y + height - 2), 0xFFE0E0E0);
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
