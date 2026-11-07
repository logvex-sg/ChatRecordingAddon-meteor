package com.logvex.addon.automation.core;

import java.util.List;

/**
 * Acquires items in Creative mode by sending real creative gives, one stack at a time.
 *
 * <p>The controller is deliberately conservative about success: a give is only counted once the
 * server has acknowledged it, and the item is only marked complete once the inventory really holds
 * the requested amount. A rejected give is retried a bounded number of times before the item is
 * reported as failed, so a server that silently drops creative packets cannot trap the workflow.
 *
 * <p>This is the generic, reusable form of the acquisition logic the SchematicDropper uses: it takes
 * a list of {@link Request}s and drives them through {@link CreativePort}, which keeps the
 * Minecraft-specific packet handling in the adapter.
 */
public final class CreativeController {
    /** One item to acquire: its registry id and how many are wanted. */
    public record Request(String itemId, int amount) {
    }

    /** Where one item stands. */
    public enum ItemStatus {
        PENDING,
        ACQUIRING,
        /** The inventory holds the requested amount. */
        DONE,
        /** The item could not be acquired after the retry budget. */
        FAILED
    }

    /** The live state of one requested item. */
    public static final class Item {
        private final Request request;
        private ItemStatus status = ItemStatus.PENDING;
        private int held;
        private String failure;

        Item(Request request) {
            this.request = request;
        }

        public Request request() {
            return request;
        }

        public String itemId() {
            return request.itemId();
        }

        public int required() {
            return request.amount();
        }

        public int held() {
            return held;
        }

        public int remaining() {
            return Math.max(0, request.amount() - held);
        }

        public ItemStatus status() {
            return status;
        }

        public String failure() {
            return failure;
        }

        /** The fraction acquired, 0..1. */
        public double fraction() {
            if (request.amount() <= 0) return 1;
            return Math.max(0, Math.min(1, (double) held / request.amount()));
        }
    }

    private final CreativePort port;
    private final CreativeEnv env;
    private int retryLimit;

    private final java.util.List<Item> items = new java.util.ArrayList<>();
    private int index;
    private int retries;
    private boolean running;
    private boolean paused;
    private String lastError;

    public CreativeController(CreativePort port, CreativeEnv env, int retryLimit) {
        this.port = port;
        this.env = env;
        this.retryLimit = Math.max(1, retryLimit);
    }

    /** Updates the retry budget, so the user can change it without recreating the controller. */
    public void setRetryLimit(int value) {
        this.retryLimit = Math.max(1, value);
    }

    /** Replaces the requested items and resets progress. */
    public void setRequests(List<Request> requests) {
        items.clear();
        index = 0;
        retries = 0;
        lastError = null;
        if (requests != null) {
            for (Request request : requests) {
                if (request != null && request.itemId() != null && !request.itemId().isBlank()
                    && request.amount() > 0) {
                    items.add(new Item(request));
                }
            }
        }
    }

    public void start() {
        running = true;
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public void stop() {
        running = false;
        paused = false;
        port.cancelGive();
    }

    public void reset() {
        stop();
        items.clear();
        index = 0;
        retries = 0;
        lastError = null;
        port.reset();
    }

    public boolean running() {
        return running;
    }

    public boolean paused() {
        return paused;
    }

    public List<Item> items() {
        return java.util.Collections.unmodifiableList(items);
    }

    /** The item currently being acquired, or {@code null} when none is. */
    public Item active() {
        if (index < 0 || index >= items.size()) return null;
        return items.get(index);
    }

    public String lastError() {
        return lastError;
    }

    /** True when every item is finished (done or failed). */
    public boolean finished() {
        for (Item item : items) {
            if (item.status == ItemStatus.PENDING || item.status == ItemStatus.ACQUIRING) return false;
        }
        return true;
    }

    /** Items acquired so far. */
    public int completedCount() {
        int done = 0;
        for (Item item : items) if (item.status == ItemStatus.DONE) done++;
        return done;
    }

    public int failedCount() {
        int failed = 0;
        for (Item item : items) if (item.status == ItemStatus.FAILED) failed++;
        return failed;
    }

    /** Overall progress as a fraction of total requested units. */
    public double progress() {
        long required = 0;
        long held = 0;
        for (Item item : items) {
            required += item.required();
            held += Math.min(item.held(), item.required());
        }
        return required == 0 ? 1 : Math.max(0, Math.min(1, (double) held / required));
    }

    /**
     * Advances the workflow by one step. Safe to call every client tick.
     *
     * @return true while there is still work to do
     */
    public boolean tick() {
        if (!running || paused || finished()) return false;

        if (!env.canRun()) {
            lastError = "No world loaded.";
            return true;
        }
        if (!port.creativeAvailable()) {
            lastError = "Creative mode is required to acquire items.";
            return true;
        }

        Item item = active();
        if (item == null) {
            advance();
            return !finished();
        }

        // Refresh how many are held: the inventory is the source of truth, not our bookkeeping.
        item.held = env.countOf(item.itemId());

        if (item.status == ItemStatus.PENDING) {
            if (!env.knownItem(item.itemId())) {
                item.status = ItemStatus.FAILED;
                item.failure = "Unknown item id.";
                advance();
                return !finished();
            }
            item.status = ItemStatus.ACQUIRING;
        }

        int remaining = item.remaining();
        if (remaining <= 0) {
            item.status = ItemStatus.DONE;
            advance();
            return !finished();
        }

        CreativePort.GiveStatus status = port.pollGive();
        if (status == CreativePort.GiveStatus.PENDING) {
            return true; // Wait for the server's acknowledgement.
        }
        if (status == CreativePort.GiveStatus.CONFIRMED) {
            retries = 0;
            return true;
        }

        // Nothing in flight: send the next stack-sized give.
        //
        // A creative give *replaces* the target slot's contents, it does not add to them, so the
        // amount is the total the slot should end up holding: the existing count plus what is still
        // missing, capped at one stack. Targeting a slot that is already full would change nothing
        // and loop forever, so a full stack is skipped in favour of an empty slot.
        int stackLimit = Math.max(1, env.stackLimit(item.itemId()));
        int slot = env.firstSlotContaining(item.itemId());
        if (slot >= 0 && env.countInSlot(slot) >= stackLimit) slot = -1;
        if (slot < 0) slot = env.firstEmptySlot();
        if (slot < 0) {
            lastError = "The inventory is full.";
            return true; // Wait for space rather than failing.
        }

        int existing = env.countInSlot(slot);
        int amount = Math.min(remaining + existing, stackLimit);
        if (amount <= existing) {
            // No room to make progress in this slot; wait for the inventory to change.
            lastError = "The inventory is full.";
            return true;
        }

        CreativePort.Give give = new CreativePort.Give(item.itemId(), slot, amount, stackLimit);
        if (!give.valid()) {
            item.status = ItemStatus.FAILED;
            item.failure = "Invalid quantity for " + item.itemId() + ".";
            advance();
            return !finished();
        }

        CreativePort.GiveStatus sent = port.give(give);
        if (sent == CreativePort.GiveStatus.CONFIRMED) {
            retries = 0;
            return true;
        }
        if (sent == CreativePort.GiveStatus.REJECTED) {
            onRejected(item, port.lastGiveFailure());
        }
        return true;
    }

    private void onRejected(Item item, String reason) {
        retries++;
        if (retries >= retryLimit) {
            item.status = ItemStatus.FAILED;
            item.failure = reason == null ? "The server rejected the give." : reason;
            retries = 0;
            advance();
        }
    }

    private void advance() {
        retries = 0;
        // Skip forward past anything already finished.
        do {
            index++;
        } while (index < items.size()
            && (items.get(index).status == ItemStatus.DONE || items.get(index).status == ItemStatus.FAILED));
    }

    /** Moves back to the given item so the user can retry it. */
    public boolean retryItem(String itemId) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (!item.itemId().equals(itemId)) continue;
            item.status = ItemStatus.PENDING;
            item.failure = null;
            index = i;
            retries = 0;
            lastError = null;
            return true;
        }
        return false;
    }

    /** Restarts the item currently being acquired. */
    public boolean restartActive() {
        Item item = active();
        if (item == null) return false;
        item.status = ItemStatus.PENDING;
        item.failure = null;
        retries = 0;
        return true;
    }

    /** A short, human-readable status for the HUD and chat. */
    public String status() {
        if (!running) return "idle";
        if (paused) return "paused";
        Item item = active();
        if (item == null) return finished() ? "done" : "idle";
        return item.itemId() + " " + item.held() + "/" + item.required();
    }
}
