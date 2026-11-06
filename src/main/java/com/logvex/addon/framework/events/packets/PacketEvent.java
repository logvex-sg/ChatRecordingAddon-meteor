package com.logvex.addon.framework.events.packets;

import net.minecraft.network.packet.Packet;

/** Posted around packet handling. Listeners subscribe to {@link Receive}. */
public abstract class PacketEvent {
    public final Packet<?> packet;

    protected PacketEvent(Packet<?> packet) {
        this.packet = packet;
    }

    /** Posted when a packet arrives from the server, before the client handles it. */
    public static final class Receive extends PacketEvent {
        public Receive(Packet<?> packet) {
            super(packet);
        }
    }

    /** Posted when a packet is sent to the server. */
    public static final class Send extends PacketEvent {
        public Send(Packet<?> packet) {
            super(packet);
        }
    }
}
