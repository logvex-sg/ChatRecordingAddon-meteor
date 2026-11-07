package com.logvex.addon.automation.core;

import java.util.List;

/**
 * Watches armour durability and warns before a piece breaks.
 *
 * <p>Only the four vanilla armour slots are considered. A slot that is empty or holds a
 * non-damageable item is reported as {@link PieceState#NOT_APPLICABLE} rather than as zero
 * durability, so "no helmet" and "a helmet about to break" are never confused — the same
 * unavailable-versus-zero distinction the rest of the addon uses.
 */
public final class ArmorGuardController {
    /** The condition of one armour slot. */
    public enum PieceState {
        /** No item, or an item that cannot take damage. */
        NOT_APPLICABLE,
        /** Above the warning threshold. */
        OK,
        /** At or below the warning threshold but above the critical one. */
        WARNING,
        /** At or below the critical threshold. */
        CRITICAL
    }

    /** A single armour slot's reading. */
    public record Piece(String slotName, String itemId, int durability, int maxDurability, PieceState state) {
        /** The remaining durability as a fraction, or -1 when not applicable. */
        public double fraction() {
            if (maxDurability <= 0) return -1;
            return Math.max(0, Math.min(1, (double) durability / maxDurability));
        }
    }

    private boolean enabled;
    private int warnPercent;
    private int criticalPercent;

    public ArmorGuardController(int warnPercent, int criticalPercent) {
        setThresholds(warnPercent, criticalPercent);
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Sets the warning and critical percentages, keeping warning strictly above critical. */
    public void setThresholds(int warnPercent, int criticalPercent) {
        int critical = Math.max(1, Math.min(99, criticalPercent));
        int warn = Math.max(critical + 1, Math.min(100, warnPercent));
        this.criticalPercent = critical;
        this.warnPercent = warn;
    }

    public int warnPercent() {
        return warnPercent;
    }

    public int criticalPercent() {
        return criticalPercent;
    }

    /** Classifies one slot. {@code maxDurability <= 0} means the item cannot take damage. */
    public PieceState classify(int durability, int maxDurability) {
        if (maxDurability <= 0) return PieceState.NOT_APPLICABLE;
        double percent = 100.0 * Math.max(0, durability) / maxDurability;
        if (percent <= criticalPercent) return PieceState.CRITICAL;
        if (percent <= warnPercent) return PieceState.WARNING;
        return PieceState.OK;
    }

    /** Builds the full reading for the four armour slots. */
    public List<Piece> evaluate(List<Piece> slots) {
        return slots == null ? List.of() : slots;
    }

    /** True when any slot needs the player's attention. */
    public boolean anyAtRisk(List<Piece> pieces) {
        if (!enabled || pieces == null) return false;
        for (Piece piece : pieces) {
            if (piece != null && (piece.state() == PieceState.WARNING || piece.state() == PieceState.CRITICAL)) {
                return true;
            }
        }
        return false;
    }

    /** The slot closest to breaking, or {@code null} when none is at risk. */
    public Piece mostUrgent(List<Piece> pieces) {
        if (pieces == null) return null;
        Piece worst = null;
        for (Piece piece : pieces) {
            if (piece == null || piece.state() == PieceState.NOT_APPLICABLE) continue;
            if (piece.state() == PieceState.OK) continue;
            if (worst == null || piece.fraction() < worst.fraction()) worst = piece;
        }
        return worst;
    }
}
