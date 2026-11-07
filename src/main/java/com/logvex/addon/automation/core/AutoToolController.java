package com.logvex.addon.automation.core;

import java.util.List;

/**
 * Chooses the best tool for the block being mined.
 *
 * <p>Selection is driven by a caller-supplied scoring function (harvest level, mining speed,
 * enchantments), so the Minecraft layer supplies the knowledge while the choice itself stays pure
 * and testable. The controller only ever returns a hotbar slot the player really has, and it keeps
 * the currently held slot when nothing scores better, so it never thrashes the hotbar.
 */
public final class AutoToolController {
    /** A candidate tool: its hotbar slot and a score where higher is better. */
    public record Candidate(int slot, String itemId, double score) {
    }

    private boolean enabled;
    private boolean swapBack;
    private int previousSlot = -1;

    public void setEnabled(boolean value) {
        this.enabled = value;
        if (!enabled) previousSlot = -1;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setSwapBack(boolean value) {
        this.swapBack = value;
    }

    public boolean swapBack() {
        return swapBack;
    }

    public void reset() {
        previousSlot = -1;
    }

    /** The hotbar slot the player was on before the last swap, or -1 when none happened. */
    public int previousSlot() {
        return previousSlot;
    }

    /**
     * Picks the best hotbar slot for a block.
     *
     * @param currentSlot the slot currently selected
     * @param candidates  every tool the player holds in the hotbar, with a score
     * @param minScore    the minimum score worth swapping for (blocks with no preferred tool use 0)
     * @return the slot to select, or {@code currentSlot} when the current one is already best
     */
    public int chooseSlot(int currentSlot, List<Candidate> candidates, double minScore) {
        if (!enabled || candidates == null || candidates.isEmpty()) return currentSlot;

        int bestSlot = currentSlot;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.slot() < 0) continue;
            if (candidate.slot() == currentSlot) {
                // Score the current slot like any other, so a better tool elsewhere still wins.
                if (candidate.score() > bestScore) {
                    bestScore = candidate.score();
                    bestSlot = candidate.slot();
                }
                continue;
            }
            if (candidate.score() > bestScore) {
                bestScore = candidate.score();
                bestSlot = candidate.slot();
            }
        }

        if (bestScore < minScore) return currentSlot;
        return bestSlot;
    }

    /** Remembers the slot to restore later when {@link #swapBack} is on. */
    public void rememberSwap(int fromSlot) {
        if (swapBack && fromSlot >= 0) previousSlot = fromSlot;
    }

    public String status() {
        return enabled ? (swapBack ? "on (swap back)" : "on") : "off";
    }
}
