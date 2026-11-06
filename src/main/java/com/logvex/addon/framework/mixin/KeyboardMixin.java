package com.logvex.addon.framework.mixin;

import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.gui.ClickGuiScreen;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keyboard entry points for the addon.
 *
 * <p>Right Shift opens the main window while a world is loaded, matching the muscle memory users of
 * already have. Key presses are also routed to the focused widget of a {@link WidgetScreen}, because
 * vanilla's {@code Screen.keyPressed} never reaches a text box on its own.
 */
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void logaddon$onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo info) {
        if (key == GLFW.GLFW_KEY_UNKNOWN || action == GLFW.GLFW_RELEASE) return;

        if (action == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            if (client.player != null && !(client.currentScreen instanceof ClickGuiScreen)) {
                client.setScreen(new ClickGuiScreen());
                info.cancel();
                return;
            }
        }

        if (!(client.currentScreen instanceof WidgetScreen screen)) return;
        if (screen.handleFocusedKey(key, scancode, modifiers)) info.cancel();
    }
}
