package com.logvex.addon.framework.mixin;

import com.logvex.addon.framework.commands.Commands;
import com.logvex.addon.framework.systems.config.ClientConfig;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the addon's own commands when a chat line starts with the configured prefix.
 *
 * <p>The line is consumed instead of being sent to the server, so the command never leaks into
 * public chat. Everything else is left untouched for the server to handle.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerCommandMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void logclient$onSendChatMessage(String message, CallbackInfo info) {
        String prefix = ClientConfig.get().prefix();
        if (message == null || prefix.isEmpty() || !message.startsWith(prefix)) return;

        try {
            Commands.dispatch(message.substring(prefix.length()));
        } catch (CommandSyntaxException e) {
            com.logvex.addon.framework.utils.player.ChatUtils.error("%s", e.getMessage());
        }
        info.cancel();
    }
}
