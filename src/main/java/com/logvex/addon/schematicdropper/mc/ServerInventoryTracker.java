package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.framework.events.packets.PacketEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * Observes the packets the server uses to correct the client's inventory.
 *
 * <p>This is the only trustworthy confirmation source: {@code clickSlot} mutates the local
 * screen handler immediately (prediction), so a local count change proves nothing. A
 * {@link ScreenHandlerSlotUpdateS2CPacket} or {@link InventoryS2CPacket} is only ever produced by
 * the server, so a new packet that names the slot we clicked is real acknowledgement.
 */
public final class ServerInventoryTracker {
    private long sequence;
    private int lastSlot = -1;
    private int lastCount = -1;
    private long lastSequenceForSlot;

    /** Monotonic counter of server inventory corrections. */
    public long sequence() {
        return sequence;
    }

    /** Sequence value of the last correction that named {@code slot}, or -1 if none. */
    public long sequenceForSlot(int slot) {
        return lastSlot == slot ? lastSequenceForSlot : -1;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket packet) {
            if (!matchesCurrentHandler(packet.getSyncId())) return;
            sequence++;
            lastSlot = packet.getSlot();
            lastCount = packet.getStack().getCount();
            lastSequenceForSlot = sequence;
        } else if (event.packet instanceof InventoryS2CPacket packet) {
            if (!matchesCurrentHandler(packet.getSyncId())) return;
            sequence++;
            lastSlot = -1;
            lastCount = -1;
            lastSequenceForSlot = sequence;
        }
    }

    private boolean matchesCurrentHandler(int syncId) {
        if (syncId == ScreenHandlerSlotUpdateS2CPacket.UPDATE_PLAYER_INVENTORY_SYNC_ID) return true;
        return mc.player.currentScreenHandler != null && syncId == mc.player.currentScreenHandler.syncId;
    }

    public void reset() {
        sequence = 0;
        lastSlot = -1;
        lastCount = -1;
        lastSequenceForSlot = 0;
    }
}
