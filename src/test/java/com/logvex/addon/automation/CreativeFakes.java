package com.logvex.addon.automation;

import com.logvex.addon.automation.core.CreativeController;
import com.logvex.addon.automation.core.CreativeEnv;
import com.logvex.addon.automation.core.CreativePort;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory doubles for the creative acquisition workflow.
 *
 * <p>The one behaviour that matters is modelled faithfully: a give changes nothing until the server
 * confirms it. A test can therefore assert that the controller never reports success from a
 * client-only change.
 */
final class CreativeFakes {
    private CreativeFakes() {
    }

    /** A 36-slot player inventory that only a confirmed give can change. */
    static final class FakeEnv implements CreativeEnv {
        static final int SLOTS = 36;

        final String[] slotItem = new String[SLOTS];
        final int[] slotCount = new int[SLOTS];
        final Map<String, Integer> stackLimits = new HashMap<>();
        final Map<String, Boolean> known = new HashMap<>();

        boolean canRun = true;
        int defaultStackLimit = 64;

        @Override
        public boolean canRun() {
            return canRun;
        }

        @Override
        public boolean knownItem(String itemId) {
            return known.getOrDefault(itemId, true);
        }

        @Override
        public int countOf(String itemId) {
            int total = 0;
            for (int i = 0; i < SLOTS; i++) {
                if (itemId != null && itemId.equals(slotItem[i])) total += slotCount[i];
            }
            return total;
        }

        @Override
        public int freeSlots() {
            int free = 0;
            for (int i = 0; i < SLOTS; i++) if (slotItem[i] == null) free++;
            return free;
        }

        @Override
        public int stackLimit(String itemId) {
            return stackLimits.getOrDefault(itemId, defaultStackLimit);
        }

        @Override
        public int firstEmptySlot() {
            for (int i = 0; i < SLOTS; i++) if (slotItem[i] == null) return i;
            return -1;
        }

        @Override
        public int firstSlotContaining(String itemId) {
            for (int i = 0; i < SLOTS; i++) if (itemId != null && itemId.equals(slotItem[i])) return i;
            return -1;
        }

        @Override
        public int countInSlot(int slot) {
            if (slot < 0 || slot >= SLOTS) return 0;
            return slotCount[slot];
        }

        @Override
        public int inventorySize() {
            return SLOTS;
        }

        void put(int slot, String itemId, int count) {
            slotItem[slot] = itemId;
            slotCount[slot] = count;
        }

        void fill() {
            for (int i = 0; i < SLOTS; i++) put(i, "minecraft:stone", 64);
        }
    }

    /** A port that only applies a give when {@link #confirmNext()} or auto-confirm is on. */
    static final class FakePort implements CreativePort {
        final FakeEnv env;
        boolean creative = true;
        boolean inCreative = true;
        boolean autoConfirm = true;
        boolean rejectAll;
        String failure;

        private Give inFlight;

        FakePort(FakeEnv env) {
            this.env = env;
        }

        @Override
        public boolean creativeAvailable() {
            return creative;
        }

        @Override
        public boolean inCreative() {
            return inCreative;
        }

        @Override
        public GiveStatus give(Give item) {
            if (rejectAll) {
                failure = "Rejected by test.";
                return GiveStatus.REJECTED;
            }
            inFlight = item;
            return GiveStatus.PENDING;
        }

        @Override
        public GiveStatus pollGive() {
            if (inFlight == null) return GiveStatus.REJECTED;
            if (!autoConfirm) return GiveStatus.PENDING;
            apply(inFlight);
            inFlight = null;
            return GiveStatus.CONFIRMED;
        }

        @Override
        public String lastGiveFailure() {
            return failure;
        }

        @Override
        public void cancelGive() {
            inFlight = null;
        }

        @Override
        public void reset() {
            inFlight = null;
        }

        /** Applies the in-flight give, as a server correction would. */
        void apply(Give give) {
            int slot = give.slot();
            env.put(slot, give.itemId(), Math.min(give.amount(), give.stackLimit()));
        }

        /** The give that is waiting for an acknowledgement, or {@code null}. */
        Give inFlight() {
            return inFlight;
        }
    }
}
