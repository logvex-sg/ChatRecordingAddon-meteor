package com.logvex.addon.framework.mixin;

import com.logvex.addon.chatlog.mc.ChatFilterService;
import com.logvex.addon.framework.Addon;
import com.logvex.addon.framework.events.game.ReceiveMessageEvent;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes every message the client adds to chat, and applies the chat filter.
 *
 * <p>The filter runs at {@code HEAD} and cancels, so a suppressed message is never stored: it does
 * not appear in the chat HUD, is not recorded and never reaches the log file. Cancelling here rather
 * than hiding after the fact is what makes the filter actually reduce chat noise.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        at = @At("HEAD"), cancellable = true)
    private void logaddon$onAddMessage(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo info) {
        ReceiveMessageEvent event = new ReceiveMessageEvent(message, indicator);
        if (ChatFilterService.get().shouldHide(event)) {
            info.cancel();
            return;
        }
        Addon.EVENT_BUS.post(event);
    }
}
