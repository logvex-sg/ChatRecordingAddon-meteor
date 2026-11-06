package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.AcquisitionAction;
import com.logvex.addon.schematicdropper.core.AcquisitionEnv;
import com.logvex.addon.schematicdropper.core.DrainDelegate;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.GivePlan;
import com.logvex.addon.schematicdropper.core.PendingGive;
import com.logvex.addon.schematicdropper.core.SlotInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory doubles for the acquisition workflow. They model the one behaviour that matters:
 * a creative give changes nothing until the server confirms it.
 */
final class AcquisitionFakes {
    private AcquisitionFakes() {
    }

    /** A 36-slot player inventory (hotbar + main) that only the fake action port can change. */
    static final class FakeEnv implements AcquisitionEnv {
        static final int SLOTS = 36;

        final String[] slotItem = new String[SLOTS];
        final int[] slotCount = new int[SLOTS];
        final Map<String, Integer> stackLimits = new HashMap<>();

        boolean canRun = true;
        boolean creative = true;
        boolean canInteract = true;
        int defaultStackLimit = 64;

        @Override
        public boolean canRun() {
            return canRun;
        }

        @Override
        public boolean isCreative() {
            return creative;
        }

        @Override
        public boolean canInteract() {
            return canInteract;
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
        public List<SlotInfo> writableSlots() {
            List<SlotInfo> out = new ArrayList<>(SLOTS);
            for (int i = 0; i < SLOTS; i++) {
                String id = slotItem[i];
                int count = slotCount[i];
                out.add(new SlotInfo(i, id, count, id == null ? 0 : limitOf(id)));
            }
            return out;
        }

        @Override
        public int stackLimitOf(String itemId) {
            if (itemId == null) return 0;
            return stackLimits.getOrDefault(itemId, defaultStackLimit);
        }

        @Override
        public boolean isKnownItem(String itemId) {
            return itemId != null && !itemId.endsWith(":nope");
        }

        int limitOf(String itemId) {
            return stackLimits.getOrDefault(itemId, defaultStackLimit);
        }

        void put(int slot, String itemId, int count) {
            slotItem[slot] = itemId;
            slotCount[slot] = count;
        }

        void fillAll(String itemId, int count) {
            for (int i = 0; i < SLOTS; i++) put(i, itemId, count);
        }

        /** Applies a confirmed give the way a server would: the stack really changes. */
        void applyGive(String itemId, int slot, int amount) {
            if (slot < 0 || slot >= SLOTS) return;
            if (slotItem[slot] == null || slotItem[slot].equals(itemId)) {
                slotItem[slot] = itemId;
                slotCount[slot] = amount;
            }
        }
    }

    /** Programmable action port. By default every give is confirmed immediately. */
    static final class FakeActions implements AcquisitionAction {
        final Deque<Outcome> outcomes = new ArrayDeque<>();
        final List<GivePlan> issued = new ArrayList<>();
        int cancelCount;

        /** When set, {@link #begin} returns {@link #controllable} so a test can resolve it by hand. */
        boolean useControllable;
        final Controllable controllable = new Controllable();

        enum Behaviour { CONFIRM, REJECT, TIMEOUT, NO_PERMISSION, PENDING, GHOST }

        record Outcome(Behaviour behaviour, int amount) {
        }

        void enqueue(Behaviour behaviour) {
            enqueue(behaviour, -1);
        }

        void enqueue(Behaviour behaviour, int amount) {
            outcomes.add(new Outcome(behaviour, amount));
        }

        @Override
        public PendingGive begin(GivePlan plan) {
            issued.add(plan);
            if (useControllable) return controllable;
            Outcome outcome = outcomes.poll();
            if (outcome == null) outcome = new Outcome(Behaviour.CONFIRM, plan.amount());

            return switch (outcome.behaviour()) {
                case CONFIRM -> new Immediate(PendingGive.Status.CONFIRMED, null,
                    outcome.amount() < 0 ? plan.amount() : outcome.amount());
                case REJECT -> new Immediate(PendingGive.Status.REJECTED, FailureType.SERVER_REJECTION, 0);
                case TIMEOUT -> new Immediate(PendingGive.Status.TIMEOUT, FailureType.SERVER_TIMEOUT, 0);
                case NO_PERMISSION -> new Immediate(PendingGive.Status.REJECTED,
                    FailureType.NO_CREATIVE_PERMISSION, 0);
                case PENDING -> new Immediate(PendingGive.Status.PENDING, null, 0);
                case GHOST -> new Immediate(PendingGive.Status.REJECTED, FailureType.INVENTORY_DESYNC, 0);
            };
        }

        @Override
        public void cancelPending() {
            cancelCount++;
        }

        /** A give the test resolves explicitly, so an in-flight operation can be held open. */
        static final class Controllable implements PendingGive {
            private volatile Status status = Status.PENDING;
            private volatile int confirmed;

            void confirm(int amount) {
                confirmed = amount;
                status = Status.CONFIRMED;
            }

            @Override
            public Status poll() {
                return status;
            }

            @Override
            public int confirmedIn() {
                return confirmed;
            }

            @Override
            public FailureType failure() {
                return null;
            }
        }
    }

    static final class Immediate implements PendingGive {
        private final Status status;
        private final FailureType failure;
        private final int confirmed;

        Immediate(Status status, FailureType failure, int confirmed) {
            this.status = status;
            this.failure = failure;
            this.confirmed = confirmed;
        }

        @Override
        public Status poll() {
            return status;
        }

        @Override
        public int confirmedIn() {
            return confirmed;
        }

        @Override
        public FailureType failure() {
            return failure;
        }
    }

    /** A drain delegate the test drives by hand. */
    static final class FakeDrain implements DrainDelegate {
        boolean active;
        boolean draining;
        boolean canBegin = true;
        int beginCount;
        int abortCount;
        Map<String, Integer> result = Map.of();

        @Override
        public boolean beginDrain() {
            if (!canBegin) return false;
            beginCount++;
            active = true;
            draining = true;
            return true;
        }

        @Override
        public boolean draining() {
            return draining;
        }

        @Override
        public void abortDrain() {
            abortCount++;
            draining = false;
            active = false;
        }

        @Override
        public Map<String, Integer> lastDrain() {
            return result;
        }

        /** Simulates the drop engine confirming {@code amounts} and finishing. */
        void complete(Map<String, Integer> amounts) {
            result = amounts;
            draining = false;
            active = false;
        }
    }
}
