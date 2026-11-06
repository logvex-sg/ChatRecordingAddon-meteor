package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.core.ActionPort;
import com.logvex.addon.schematicdropper.core.DropPlan;
import com.logvex.addon.schematicdropper.core.EnvPort;
import com.logvex.addon.schematicdropper.core.FailureType;
import com.logvex.addon.schematicdropper.core.PendingDrop;
import com.logvex.addon.schematicdropper.core.Rotation;
import com.logvex.addon.schematicdropper.core.RotationMath;
import com.logvex.addon.schematicdropper.core.RotationPort;
import com.logvex.addon.schematicdropper.core.StackEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory doubles for the engine's ports. They model the one behaviour that matters for
 * correctness: a local click changes nothing until the server confirms it.
 */
public final class Fakes {
    private Fakes() {
    }

    /** Mutable inventory keyed by registry id. */
    public static final class FakeEnv implements EnvPort {
        public final Map<String, Integer> items = new LinkedHashMap<>();
        public boolean canRun = true;
        public boolean canInteract = true;
        public Rotation desired = new Rotation(0f, 0f);
        public Rotation current = new Rotation(0f, 0f);
        /** When true, stacks are keyed by the order they were added; otherwise a single stack per item. */
        public final Map<String, List<StackEntry>> stacks = new LinkedHashMap<>();

        @Override
        public boolean canRun() {
            return canRun;
        }

        @Override
        public boolean canInteract() {
            return canInteract;
        }

        @Override
        public int countOf(String itemId) {
            return items.getOrDefault(itemId, 0);
        }

        @Override
        public List<StackEntry> stacksOf(String itemId) {
            List<StackEntry> explicit = stacks.get(itemId);
            if (explicit != null) return new ArrayList<>(explicit);
            int count = countOf(itemId);
            if (count <= 0) return List.of();
            return List.of(new StackEntry(0, count));
        }

        @Override
        public Map<String, Integer> inventorySnapshot() {
            return new LinkedHashMap<>(items);
        }

        @Override
        public Rotation currentRotation() {
            return current;
        }

        @Override
        public Rotation desiredRotation() {
            return desired;
        }

        public void set(String itemId, int count) {
            items.put(itemId, count);
            stacks.remove(itemId);
        }

        public void setStacks(String itemId, StackEntry... entries) {
            stacks.put(itemId, new ArrayList<>(List.of(entries)));
            int total = 0;
            for (StackEntry entry : entries) total += entry.count();
            items.put(itemId, total);
        }

        /** Applies a confirmed transfer the way a server would: the items really leave. */
        public void consume(String itemId, int amount) {
            int remaining = amount;
            List<StackEntry> current = stacks.get(itemId);
            if (current != null) {
                List<StackEntry> updated = new ArrayList<>(current.size());
                for (StackEntry entry : current) {
                    int take = Math.min(entry.count(), remaining);
                    remaining -= take;
                    int left = entry.count() - take;
                    if (left > 0) updated.add(new StackEntry(entry.slot(), left));
                }
                stacks.put(itemId, updated);
            }
            int total = Math.max(0, items.getOrDefault(itemId, 0) - amount);
            if (total == 0) items.remove(itemId);
            else items.put(itemId, total);
        }
    }

    /** Programmable action port. By default every drop is confirmed immediately. */
    public static final class FakeActions implements ActionPort {
        public final Deque<Outcome> outcomes = new ArrayDeque<>();
        public final List<DropPlan> issued = new ArrayList<>();
        public int prepareCalls;
        public boolean prepareReady = true;
        public int cancelCount;

        /** When set, {@link #begin} returns {@link #controllable} so a test can resolve it by hand. */
        public boolean useControllable;
        public final Controllable controllable = new Controllable();

        /** How the next began drop should behave. */
        public enum Behaviour { CONFIRM, REJECT, TIMEOUT, PENDING }

        public record Outcome(Behaviour behaviour, int amount) {
        }

        public void enqueue(Behaviour behaviour) {
            enqueue(behaviour, 1);
        }

        public void enqueue(Behaviour behaviour, int amount) {
            outcomes.add(new Outcome(behaviour, amount));
        }

        @Override
        public boolean prepare() {
            prepareCalls++;
            return prepareReady;
        }

        @Override
        public PendingDrop begin(DropPlan plan) {
            issued.add(plan);
            if (useControllable) return controllable;
            Outcome outcome = outcomes.poll();
            if (outcome == null) outcome = new Outcome(Behaviour.CONFIRM, plan.expectedOut());

            return switch (outcome.behaviour()) {
                case CONFIRM -> new Immediate(PendingDrop.Status.CONFIRMED, null, outcome.amount());
                case REJECT -> new Immediate(PendingDrop.Status.REJECTED, FailureType.SERVER_REJECTION, 0);
                case TIMEOUT -> new Immediate(PendingDrop.Status.TIMEOUT, FailureType.SERVER_TIMEOUT, 0);
                case PENDING -> new Immediate(PendingDrop.Status.PENDING, null, 0);
            };
        }

        @Override
        public void cancelPending() {
            cancelCount++;
        }

        /** A transfer the test resolves explicitly, so an in-flight operation can be held open. */
        public static final class Controllable implements PendingDrop {
            private volatile Status status = Status.PENDING;
            private volatile int confirmed;

            public void confirm(int amount) {
                confirmed = amount;
                status = Status.CONFIRMED;
            }

            public void reject(FailureType failure) {
                status = Status.REJECTED;
            }

            public void timeout() {
                status = Status.TIMEOUT;
            }

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
                return null;
            }
        }
    }

    public static final class Immediate implements PendingDrop {
        private final Status status;
        private final FailureType failure;
        private final int confirmed;

        public Immediate(Status status, FailureType failure, int confirmed) {
            this.status = status;
            this.failure = failure;
            this.confirmed = confirmed;
        }

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
            return failure;
        }
    }

    /** Records the rotation the engine asked for. Instant mode snaps, smooth mode steps. */
    public static final class FakeRotation implements RotationPort {
        public Rotation lastApplied;
        public Rotation restored;
        public int applyCount;
        public boolean lastInstant;

        @Override
        public Rotation apply(Rotation target, boolean instant, float speed) {
            applyCount++;
            lastInstant = instant;
            if (lastApplied == null || instant) {
                lastApplied = target;
            } else {
                lastApplied = new Rotation(
                    RotationMath.stepToward(lastApplied.yaw(), target.yaw(), speed),
                    RotationMath.stepToward(lastApplied.pitch(), target.pitch(), speed)
                );
            }
            return lastApplied;
        }

        @Override
        public void restore(Rotation rotation) {
            restored = rotation;
        }

        @Override
        public void reset() {
            lastApplied = null;
        }
    }
}
