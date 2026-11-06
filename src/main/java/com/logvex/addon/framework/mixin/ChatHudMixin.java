package com.logvex.addon.framework.mixin;

import com.logvex.addon.framework.MeteorClient;
import com.logvex.addon.framework.events.game.ReceiveMessageEvent;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes every message the client adds to chat, before it is stored. */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        at = @At("HEAD"))
    private void logaddon$onAddMessage(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo info) {
        MeteorClient.EVENT_BUS.post(new ReceiveMessageEvent(message, indicator));
    }
}
