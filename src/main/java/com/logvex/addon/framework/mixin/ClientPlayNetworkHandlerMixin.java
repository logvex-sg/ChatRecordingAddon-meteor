package com.logvex.addon.framework.mixin;

import com.logvex.addon.framework.MeteorClient;
import com.logvex.addon.framework.events.game.GameJoinedEvent;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.events.packets.PacketEvent;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reports joins and leaves, and publishes incoming packets. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    private boolean logaddon$worldNotNull;

    @Inject(method = "onGameJoin", at = @At("HEAD"))
    private void logaddon$onGameJoinHead(GameJoinS2CPacket packet, CallbackInfo info) {
        logaddon$worldNotNull = net.minecraft.client.MinecraftClient.getInstance().world != null;
    }

    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void logaddon$onGameJoinTail(GameJoinS2CPacket packet, CallbackInfo info) {
        // A reconfiguration join replaces an existing world, which the addon must see as a leave
        // before the new join, or a session would never be closed.
        if (logaddon$worldNotNull) MeteorClient.EVENT_BUS.post(new GameLeftEvent());
        MeteorClient.EVENT_BUS.post(new GameJoinedEvent());
    }
}
