package com.logvex.addon.assistant.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether another player may run one of this client's macros.
 *
 * <p>This is the narrowest gate in the assistant, and it has to be. Running a macro sends a command
 * <em>as the operator</em>: the server attributes it to the operator's account and applies the
 * operator's permissions. A stranger who can invoke {@code /pay} through this client is spending the
 * operator's money. So the default is off, the allow-list is empty-means-everyone only once the
 * feature is explicitly switched on, and each sender is rate-limited so one player cannot use the
 * operator's account as a command proxy.
 *
 * <p>Kept free of Minecraft types so the rules — which are the security boundary — can be tested
 * directly. The caller supplies the sender name, the moment, and whether the macro was shared.
 */
public final class RemoteMacroGate {
    /** Why a request was turned away, or {@link #ALLOWED} when it was not. */
    public enum Verdict {
        ALLOWED,
        /** The feature is switched off. */
        DISABLED,
        /** The macro itself is not marked shared. */
        NOT_SHARED,
        /** The sender is not on the allow-list. */
        NOT_ALLOWED,
        /** The sender asked again too soon. */
        COOLDOWN,
        /** The sender's line was malformed. */
        INVALID;

        public boolean allowed() {
            return this == ALLOWED;
        }

        /** A short reason, for the log and for the operator's local notice. */
        public String reason() {
            return switch (this) {
                case ALLOWED -> "";
                case DISABLED -> "remote macros are switched off";
                case NOT_SHARED -> "that macro is not shared";
                case NOT_ALLOWED -> "that player is not on the macro allow-list";
                case COOLDOWN -> "that player is asking too often";
                case INVALID -> "the request was malformed";
            };
        }
    }

    private final boolean enabled;
    private final List<String> allowList;
    private final long cooldownMs;

    /** When each sender last ran a macro, keyed by lower-cased name. */
    private final Map<String, Long> lastRun = new HashMap<>();

    public RemoteMacroGate(boolean enabled, List<String> allowList, long cooldownMs) {
        this.enabled = enabled;
        this.allowList = allowList == null ? List.of() : List.copyOf(allowList);
        this.cooldownMs = Math.max(0, cooldownMs);
    }

    /** The shipped policy: off, nobody allowed, a five-second floor. */
    public static RemoteMacroGate disabled() {
        return new RemoteMacroGate(false, List.of(), 5_000L);
    }

    /**
     * Decides whether {@code sender} may run {@code macro} at {@code now}.
     *
     * <p>A verdict is read-only: nothing is recorded until {@link #record} is called, so a request
     * that is refused does not consume the sender's cooldown and cannot be used to lock a player out
     * of a macro they are entitled to run.
     */
    public Verdict check(String sender, ChatMacro macro, long now) {
        if (!enabled) return Verdict.DISABLED;
        if (macro == null || !macro.valid()) return Verdict.INVALID;
        if (!macro.remote()) return Verdict.NOT_SHARED;
        String name = normalise(sender);
        if (name.isEmpty()) return Verdict.INVALID;
        if (!permitted(name)) return Verdict.NOT_ALLOWED;
        Long last = lastRun.get(name);
        if (last != null && cooldownMs > 0 && now - last < cooldownMs) return Verdict.COOLDOWN;
        return Verdict.ALLOWED;
    }

    /** Records that {@code sender} ran a macro, starting their cooldown. */
    public void record(String sender, long now) {
        String name = normalise(sender);
        if (name.isEmpty()) return;
        lastRun.put(name, now);
        // A busy server would otherwise grow this map for every player who ever asked once.
        if (lastRun.size() > 512) {
            long cutoff = now - Math.max(cooldownMs, 60_000L);
            lastRun.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    /** Forgets every cooldown, for a world change where the players are no longer the same. */
    public void reset() {
        lastRun.clear();
    }

    /** How many senders are being tracked, for a test. */
    public int tracked() {
        return lastRun.size();
    }

    /** True when {@code name} is allowed; an empty allow-list means every player once enabled. */
    private boolean permitted(String name) {
        if (allowList.isEmpty()) return true;
        for (String allowed : allowList) {
            if (normalise(allowed).equals(name)) return true;
        }
        return false;
    }

    private static String normalise(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }
}