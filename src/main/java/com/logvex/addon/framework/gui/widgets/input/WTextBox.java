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
 * Editing keys (backspace, delete, arrows, home, end, shift-arrows, Ctrl+A/C/X/V) are handled here
 * rather than leaking to the screen, and the text, caret and selection live in {@link TextEdit} so
 * their rules are testable without a game window. The border eases towards the accent while focused,
 * the selection is drawn behind the text, and a blinking caret marks the insertion point.
 */
public class WTextBox extends WWidget {
    /** Fires on every edit, for callers that filter or preview as the user types. */
    public Runnable action;
    /** Fires only when Enter is pressed, for a field that commits a value. */
    public Runnable submit;

    private final String placeholder;
    private boolean focused;
    private boolean dragging;
    private TextEdit edit;
    private final Animator fillAnim = Animator.of(0);

    public WTextBox(String text, String placeholder) {
        this.edit = TextEdit.of(text);
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    public String get() {
        return edit.text();
    }

    public void set(String text) {
        this.edit = TextEdit.of(text);
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (focused) {
            this.edit = edit.moveCaret(edit.text().length(), false);
        } else {
            this.dragging = false;
        }
    }

    /** The selection the field currently holds, or the empty string. Exposed for tests and callers. */
    public String selectedText() {
        return edit.selected();
    }

    private void apply(TextEdit next) {
        boolean changed = !next.text().equals(edit.text());
        this.edit = next;
        if (changed) changed();
    }

    private void changed() {
        if (action != null) action.run();
    }

    /** The caret position nearest a horizontal mouse coordinate, for click and drag selection. */
    private int caretAt(double mouseX) {
        var tr = MinecraftClient.getInstance().textRenderer;
        String text = edit.text();
        int[] widths = new int[text.length() + 1];
        for (int i = 1; i <= text.length(); i++) widths[i] = tr.getWidth(text.substring(0, i));
        return TextEdit.caretAt(mouseX - (x + 5), widths);
    }

    @Override
    public void calculateSize() {
        height = MinecraftClient.getInstance().textRenderer.fontHeight + 8;
        width = Math.max(100, MinecraftClient.getInstance().textRenderer.getWidth(edit.text()) + 12);
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
                ? new meteordevelopment.meteorclient.utils.render.color.Color(120, 160, 255) : theme.glow, 70), 3, 2);
        }

        var tr = MinecraftClient.getInstance().textRenderer;
        String text = edit.text();
        if (focused && edit.hasSelection()) {
            int left = (int) x + 5 + tr.getWidth(text.substring(0, edit.start()));
            int right = (int) x + 5 + tr.getWidth(text.substring(0, edit.end()));
            Render2D.rect(context, left, y + 3, Math.max(1, right - left), height - 6,
                Render2D.argb(theme == null
                    ? new meteordevelopment.meteorclient.utils.render.color.Color(90, 130, 220, 140)
                    : theme.accent, 110));
        }
        String shown = text.isEmpty() && !focused ? placeholder : text;
        int color = theme == null ? 0xFFE0E0E0 : Render2D.argb(theme.text);
        if (text.isEmpty() && !focused) color = theme == null ? 0xFF808080 : Render2D.argb(theme.muted, 200);
        context.drawTextWithShadow(tr, shown, (int) x + 5, (int) y + 4, color);

        if (focused) {
            // A caret that blinks on a one-second cycle so it reads as a live cursor.
            if ((AnimationClock.time() % 1.0) < 0.5) {
                int caretX = (int) x + 5 + tr.getWidth(text.substring(0, edit.caret()));
                Render2D.rect(context, caretX, y + 3, 1, height - 6, Render2D.argb(theme == null
                    ? new meteordevelopment.meteorclient.utils.render.color.Color(224, 224, 224) : theme.accent));
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        focused = isMouseOver(mouseX, mouseY);
        if (!focused) {
            dragging = false;
            return false;
        }
        // A click places the caret where the pointer is and starts a possible drag-selection;
        // jumping the caret to the end, as the field used to, made copying part of it impossible.
        int at = caretAt(mouseX);
        // Shift-click extends from the existing anchor rather than starting a new selection.
        edit = edit.moveCaret(at, net.minecraft.client.gui.screen.Screen.hasShiftDown());
        dragging = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!focused || !dragging) return false;
        edit = edit.dragTo(caretAt(mouseX));
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;
        boolean shift = net.minecraft.client.gui.screen.Screen.hasShiftDown();
        // The clipboard keys are handled here rather than left to the window, so a rule or an item
        // id can be brought in from outside the game. Without this a field only accepted typing,
        // which is the commonest reason a long rule "would not let me configure it".
        if (net.minecraft.client.gui.screen.Screen.hasControlDown()) {
            switch (keyCode) {
                case 86 -> { // V
                    String pasted = MinecraftClient.getInstance().keyboard.getClipboard();
                    if (pasted != null && !pasted.isEmpty()) apply(edit.insert(TextEdit.sanitise(pasted)));
                    return true;
                }
                case 88 -> { // X
                    if (edit.hasSelection()) {
                        MinecraftClient.getInstance().keyboard.setClipboard(edit.selected());
                        apply(edit.deleteSelection());
                    }
                    return true;
                }
                case 67 -> { // C
                    if (edit.hasSelection()) {
                        MinecraftClient.getInstance().keyboard.setClipboard(edit.selected());
                    }
                    return true;
                }
                case 65 -> { // A
                    edit = edit.selectAll();
                    return true;
                }
                default -> { }
            }
        }
        switch (keyCode) {
            case 259 -> { // backspace
                apply(edit.backspace());
                return true;
            }
            case 261 -> { // delete
                apply(edit.deleteForward());
                return true;
            }
            case 263 -> { // left
                edit = edit.moveCaret(edit.caret() - 1, shift);
                return true;
            }
            case 262 -> { // right
                edit = edit.moveCaret(edit.caret() + 1, shift);
                return true;
            }
            case 268 -> { // home
                edit = edit.moveCaret(0, shift);
                return true;
            }
            case 269 -> { // end
                edit = edit.moveCaret(edit.text().length(), shift);
                return true;
            }
            case 257, 335 -> { // enter
                changed();
                if (submit != null) submit.run();
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
        apply(edit.insert(String.valueOf(chr)));
        return true;
    }
}
