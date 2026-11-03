package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.AcquisitionAction;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.GivePlan;
import com.logvex.addon.schematicdropper.core.PendingGive;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Performs real, server-synchronised Creative inventory gives.
 *
 * <p>An item is created with {@link net.minecraft.client.network.ClientPlayerInteractionManager#clickCreativeStack},
 * the same call vanilla's Creative inventory uses: it sends a
 * {@code CreativeInventoryActionC2SPacket} naming a {@link net.minecraft.screen.PlayerScreenHandler}
 * slot. Because that handler's slot ids are the vanilla player-inventory ids
 * ({@code 36 + hotbar} for indices 0..8, the index itself for 9..35), a slot index maps to its
 * id with {@link #slotIdOf(int)}.
 *
 * <p>The server is the only trustworthy source of confirmation. A {@code clickCreativeStack} only
 * sends a packet, so a new {@link ScreenHandlerSlotUpdateS2CPacket} for the player inventory that
 * names the slot we wrote, followed by that slot really holding the requested item, is what marks
 * the give as confirmed. Anything else is reported as a rejection, so a client-only prediction
 * can never be mistaken for a real give.
 */
public final class CreativeInventoryController implements AcquisitionAction {
    /** Sync id the server uses for player-inventory slot corrections. */
    private static final int PLAYER_INVENTORY_SYNC_ID =
        ScreenHandlerSlotUpdateS2CPacket.UPDATE_PLAYER_INVENTORY_SYNC_ID;

    private long sequence;
    private int lastSlot = -1;
    private long lastSlotSequence;

    private Pending pending;

    /** Maps a player-inventory index (0..35) to its {@link ScreenHandler} slot id. */
    public static int slotIdOf(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    /** Maps a {@link ScreenHandler} slot id back to a player-inventory index, or -1. */
    public static int indexOf(int slotId) {
        if (slotId >= 36 && slotId <= 44) return slotId - 36;
        if (slotId >= 9 && slotId <= 35) return slotId;
        return -1;
    }

    @Override
    public PendingGive begin(GivePlan plan) {
        if (mc.player == null || mc.interactionManager == null) {
            return new Immediate(PendingGive.Status.REJECTED, FailureType.NOT_READY);
        }
        if (!plan.valid()) {
            return new Immediate(PendingGive.Status.REJECTED, FailureType.INVALID_QUANTITY);
        }

        Item item = item(plan.itemId());
        if (item == null) {
            return new Immediate(PendingGive.Status.REJECTED, FailureType.UNKNOWN_ITEM);
        }
        if (!mc.interactionManager.hasCreativeInventory()) {
            return new Immediate(PendingGive.Status.REJECTED, FailureType.NOT_CREATIVE);
        }

        int slotId = slotIdOf(plan.slot());
        ScreenHandler handler = mc.player.playerScreenHandler;
        if (handler == null || slotId < 0 || slotId >= handler.slots.size()) {
            return new Immediate(PendingGive.Status.REJECTED, FailureType.SLOT_INVALID);
        }

        ItemStack stack = new ItemStack(item, plan.amount());
        long baseline = sequence;
        pending = new Pending(plan, slotId, baseline);
        mc.interactionManager.clickCreativeStack(stack, slotId);
        return pending;
    }

    @Override
    public void cancelPending() {
        pending = null;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket packet) {
            if (packet.getSyncId() != PLAYER_INVENTORY_SYNC_ID) return;
            sequence++;
            lastSlot = packet.getSlot();
            lastSlotSequence = sequence;
        } else if (event.packet instanceof InventoryS2CPacket packet) {
            if (packet.getSyncId() != PLAYER_INVENTORY_SYNC_ID) return;
            sequence++;
            lastSlot = -1;
            lastSlotSequence = sequence;
        }
    }

    /** Resets the observation counter, e.g. on reconnect. */
    public void reset() {
        sequence = 0;
        lastSlot = -1;
        lastSlotSequence = 0;
        pending = null;
    }

    private final class Pending implements PendingGive {
        private final GivePlan plan;
        private final int slotId;
        private final long baseline;
        private Status status = Status.PENDING;
        private FailureType failure;
        private int confirmedIn;

        Pending(GivePlan plan, int slotId, long baseline) {
            this.plan = plan;
            this.slotId = slotId;
            this.baseline = baseline;
        }

        @Override
        public Status poll() {
            if (status != Status.PENDING) return status;

            if (lastSlot == slotId && lastSlotSequence > baseline) {
                status = verify() ? Status.CONFIRMED : Status.REJECTED;
                if (status == Status.CONFIRMED) confirmedIn = plan.amount();
                else failure = FailureType.SERVER_REJECTION;
            }
            return status;
        }

        /** The slot was corrected by the server; make sure it really holds what we asked for. */
        private boolean verify() {
            if (mc.player == null) return false;
            ScreenHandler handler = mc.player.playerScreenHandler;
            if (handler == null || slotId >= handler.slots.size()) return false;

            ItemStack after = handler.getSlot(slotId).getStack();
            Item expected = item(plan.itemId());
            if (expected == null || after.isEmpty() || !after.isOf(expected)) return false;
            return after.getCount() >= Math.min(plan.amount(), after.getMaxCount());
        }

        @Override
        public int confirmedIn() {
            return confirmedIn;
        }

        @Override
        public FailureType failure() {
            return failure;
        }
    }

    private record Immediate(Status status, FailureType failureType) implements PendingGive {
        @Override
        public Status poll() {
            return status;
        }

        @Override
        public int confirmedIn() {
            return 0;
        }

        @Override
        public FailureType failure() {
            return failureType;
        }
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }
}
