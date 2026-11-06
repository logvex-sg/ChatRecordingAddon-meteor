package com.logvex.addon.framework.mixin;

import com.logvex.addon.framework.MeteorClient;
import com.logvex.addon.framework.events.packets.PacketEvent;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes incoming packets just before the client handles them. */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Inject(method = "handlePacket", at = @At("HEAD"))
    private static void logaddon$onHandlePacket(Packet<?> packet, PacketListener listener, CallbackInfo info) {
        MeteorClient.EVENT_BUS.post(new PacketEvent.Receive(packet));
    }
}
