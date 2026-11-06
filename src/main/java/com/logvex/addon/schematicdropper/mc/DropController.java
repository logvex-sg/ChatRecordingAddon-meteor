package com.logvex.addon.schematicdropper.mc;

import com.logvex.addon.schematicdropper.core.ActionPort;
import com.logvex.addon.schematicdropper.core.DropPlan;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.InventoryMode;
import com.logvex.addon.schematicdropper.core.PendingDrop;
import com.logvex.addon.framework.utils.player.SlotUtils;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.function.Supplier;

import static com.logvex.addon.framework.Addon.mc;

/**
 * Performs real, server-synchronised inventory drops.
 *
 * <p>Dropping uses {@link SlotActionType#THROW}, the same action vanilla uses for the drop key.
 * {@code clickSlot} applies the change locally right away (prediction), so this controller treats
 * the local change as unconfirmed and waits for a matching server correction packet before
 * reporting {@link PendingDrop.Status#CONFIRMED}. A correction that names the clicked slot but
 * carries a different count is treated as a rejection, which is what a ghost item looks like.
 */
public final class DropController implements ActionPort {
    /** Correction-packet sequence recorded when the click was sent. */
    private long baselineSequence;
    private int clickedSlot = -1;
    private int expectedOut;
    private int beforeCount;
    private long sinceMs;

    private PendingDrop active;
    private int inventoryScreenWaitTicks;

    private final ServerInventoryTracker tracker;
    private final Supplier<InventoryMode> modeSupplier;

    public DropController(ServerInventoryTracker tracker, Supplier<InventoryMode> modeSupplier) {
        this.tracker = tracker;
        this.modeSupplier = modeSupplier;
    }

    @Override
    public boolean prepare() {
        if (mc.player == null) return false;
        InventoryMode mode = modeSupplier == null ? InventoryMode.SILENT : modeSupplier.get();
        if (mode == InventoryMode.SILENT) return true;

        if (mc.currentScreen instanceof InventoryScreen) {
            inventoryScreenWaitTicks = 0;
            return true;
        }
        if (mc.currentScreen == null) {
            mc.setScreen(new InventoryScreen(mc.player));
        }
        inventoryScreenWaitTicks++;
        return inventoryScreenWaitTicks > 20;
    }

    @Override
    public PendingDrop begin(DropPlan plan) {
        if (mc.player == null || mc.interactionManager == null) {
            return new Immediate(PendingDrop.Status.REJECTED, FailureType.SCREEN_INVALID, 0);
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) {
            return new Immediate(PendingDrop.Status.REJECTED, FailureType.SCREEN_INVALID, 0);
        }

        int slotId = SlotUtils.indexToId(plan.slot());
        if (slotId < 0 || slotId >= handler.slots.size()) {
            return new Immediate(PendingDrop.Status.REJECTED, FailureType.SLOT_INVALID, 0);
        }

        ItemStack before = handler.getSlot(slotId).getStack();
        Item item = item(plan.itemId());
        if (before.isEmpty() || item == null || !before.isOf(item)) {
            return new Immediate(PendingDrop.Status.REJECTED, FailureType.INVENTORY_CHANGED, 0);
        }

        clickedSlot = slotId;
        expectedOut = Math.min(plan.expectedOut(), before.getCount());
        beforeCount = before.getCount();
        baselineSequence = tracker.sequence();
        sinceMs = System.currentTimeMillis();

        mc.interactionManager.clickSlot(handler.syncId, slotId, 1, SlotActionType.THROW, mc.player);

        active = new Pending();
        return active;
    }

    @Override
    public void cancelPending() {
        active = null;
        clickedSlot = -1;
        inventoryScreenWaitTicks = 0;
    }

    private PendingDrop.Status poll() {
        long now = System.currentTimeMillis();

        if (tracker.sequence() == baselineSequence) {
            return PendingDrop.Status.PENDING;
        }

        long slotSequence = tracker.sequenceForSlot(clickedSlot);
        if (slotSequence < 0 || slotSequence <= baselineSequence) {
            // The correction did not name our slot; treat as a desync rather than confirming.
            return PendingDrop.Status.REJECTED;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || clickedSlot >= handler.slots.size()) {
            return PendingDrop.Status.REJECTED;
        }

        ItemStack after = handler.getSlot(clickedSlot).getStack();
        int removed = beforeCount - after.getCount();

        if (removed == expectedOut) return PendingDrop.Status.CONFIRMED;
        if (removed <= 0) return PendingDrop.Status.REJECTED;
        // A partial outcome: some items left but not what was asked for.
        return PendingDrop.Status.REJECTED;
    }

    private final class Pending implements PendingDrop {
        private Status status = Status.PENDING;
        private FailureType failure;
        private int confirmedOut;

        @Override
        public Status poll() {
            if (status != Status.PENDING) return status;

            Status next = DropController.this.poll();
            switch (next) {
                case CONFIRMED -> {
                    confirmedOut = expectedOut;
                    status = Status.CONFIRMED;
                }
                case REJECTED -> {
                    failure = FailureType.INVENTORY_DESYNC;
                    status = Status.REJECTED;
                }
                default -> { /* still pending */ }
            }
            return status;
        }

        @Override
        public int confirmedOut() {
            return confirmedOut;
        }

        @Override
        public FailureType failure() {
            return failure;
        }
    }

    private record Immediate(Status status, FailureType failureType, int confirmed) implements PendingDrop {
        @Override
        public Status poll() {
            return status;
        }

        @Override
        public int confirmedOut() {
            return confirmed;
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
