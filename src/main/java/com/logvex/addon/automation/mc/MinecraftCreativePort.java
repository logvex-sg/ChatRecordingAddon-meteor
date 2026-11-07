package com.logvex.addon.automation.mc;

import com.logvex.addon.automation.core.CreativePort;
import com.logvex.addon.framework.events.packets.PacketEvent;
import com.logvex.addon.framework.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;

import static com.logvex.addon.framework.LogClientContext.mc;

/**
 * {@link CreativePort} backed by real, server-synchronised Creative inventory gives.
 *
 * <p>An item is created with {@link net.minecraft.client.network.ClientPlayerInteractionManager#clickCreativeStack},
 * the same call vanilla's Creative inventory uses: it sends a {@code CreativeInventoryActionC2SPacket}
 * naming a {@link net.minecraft.screen.PlayerScreenHandler} slot. The server is the only trustworthy
 * source of confirmation — a {@code clickCreativeStack} only sends a packet, so a new
 * {@link ScreenHandlerSlotUpdateS2CPacket} for the player inventory that names the slot we wrote,
 * followed by that slot really holding the requested item, is what marks the give as confirmed.
 * Anything else is reported as a rejection, so a client-only prediction can never be mistaken for a
 * real give.
 *
 * <p>A give that is never acknowledged is bounded by {@link #TIMEOUT_MS} and reported as a
 * rejection, so a server that silently drops creative packets cannot stall the workflow forever.
 */
public final class MinecraftCreativePort implements CreativePort {
    /** How long to wait for the server to acknowledge a give before giving up on it. */
    private static final long TIMEOUT_MS = 2000;

    private static final int PLAYER_INVENTORY_SYNC_ID =
        ScreenHandlerSlotUpdateS2CPacket.UPDATE_PLAYER_INVENTORY_SYNC_ID;

    private long sequence;
    private int lastSlot = -1;
    private long lastSlotSequence;

    private Pending pending;
    private String lastFailure;

    /** Subscribes to the packet stream so the server's corrections can be observed. */
    public MinecraftCreativePort() {
        com.logvex.addon.framework.LogClientContext.EVENT_BUS.subscribe(this);
    }

    @Override
    public boolean creativeAvailable() {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (!mc.interactionManager.hasCreativeInventory()) return false;
        return mc.player.isCreative() || mc.interactionManager.getCurrentGameMode().isCreative();
    }

    @Override
    public boolean inCreative() {
        if (mc.player == null) return false;
        return mc.player.isCreative();
    }

    @Override
    public GiveStatus give(Give item) {
        lastFailure = null;
        if (mc.player == null || mc.interactionManager == null) {
            lastFailure = "No world loaded.";
            return GiveStatus.REJECTED;
        }
        if (item == null || !item.valid()) {
            lastFailure = "Invalid quantity or slot.";
            return GiveStatus.REJECTED;
        }
        ItemStack stack = stackOf(item.itemId(), item.amount());
        if (stack == null) {
            lastFailure = "Unknown item id: " + item.itemId();
            return GiveStatus.REJECTED;
        }
        if (!creativeAvailable()) {
            lastFailure = "Creative mode is required.";
            return GiveStatus.REJECTED;
        }

        int slotId = slotIdOf(item.slot());
        ScreenHandler handler = mc.player.playerScreenHandler;
        if (handler == null || slotId < 0 || slotId >= handler.slots.size()) {
            lastFailure = "That inventory slot is not available.";
            return GiveStatus.REJECTED;
        }

        pending = new Pending(item, slotId, sequence, System.currentTimeMillis());
        mc.interactionManager.clickCreativeStack(stack, slotId);
        return GiveStatus.PENDING;
    }

    @Override
    public GiveStatus pollGive() {
        if (pending == null) return GiveStatus.REJECTED;

        if (pending.poll()) {
            if (pending.status == GiveStatus.REJECTED) lastFailure = pending.failure;
            GiveStatus status = pending.status;
            pending = null;
            return status;
        }
        return GiveStatus.PENDING;
    }

    @Override
    public String lastGiveFailure() {
        return lastFailure;
    }

    @Override
    public void cancelGive() {
        pending = null;
    }

    @Override
    public void reset() {
        sequence = 0;
        lastSlot = -1;
        lastSlotSequence = 0;
        pending = null;
        lastFailure = null;
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

    /** Maps a player-inventory index (0..35) to its screen-handler slot id. */
    public static int slotIdOf(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    private static ItemStack stackOf(String itemId, int amount) {
        Item item = item(itemId);
        if (item == null) return null;
        return new ItemStack(item, amount);
    }

    private static Item item(String itemId) {
        if (itemId == null) return null;
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }

    /** One in-flight give, confirmed only by a matching server-side slot correction. */
    private final class Pending {
        private final Give item;
        private final int slotId;
        private final long baseline;
        private final long startedAt;
        private GiveStatus status = GiveStatus.PENDING;
        private String failure;

        Pending(Give item, int slotId, long baseline, long startedAt) {
            this.item = item;
            this.slotId = slotId;
            this.baseline = baseline;
            this.startedAt = startedAt;
        }

        /** @return true once the give reached a terminal status */
        boolean poll() {
            if (status != GiveStatus.PENDING) return true;

            if (lastSlot == slotId && lastSlotSequence > baseline) {
                if (verify()) {
                    status = GiveStatus.CONFIRMED;
                } else {
                    status = GiveStatus.REJECTED;
                    failure = "The server rejected the give.";
                }
                return true;
            }
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                status = GiveStatus.REJECTED;
                failure = "The server did not acknowledge the give.";
                return true;
            }
            return false;
        }

        /** The slot was corrected by the server; make sure it really holds what we asked for. */
        private boolean verify() {
            if (mc.player == null) return false;
            ScreenHandler handler = mc.player.playerScreenHandler;
            if (handler == null || slotId >= handler.slots.size()) return false;

            ItemStack after = handler.getSlot(slotId).getStack();
            Item expected = item(item.itemId());
            if (expected == null || after.isEmpty() || !after.isOf(expected)) return false;
            return after.getCount() >= Math.min(item.amount(), after.getMaxCount());
        }
    }
}
