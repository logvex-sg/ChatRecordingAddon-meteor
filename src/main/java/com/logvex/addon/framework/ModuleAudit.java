package com.logvex.addon.framework;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds module names that are claimed more than once.
 *
 * <p>Meteor keys its registry by class but de-duplicates on {@code Module.name}: adding a module
 * whose name is already taken removes the earlier one. That makes a name clash destructive rather
 * than cosmetic — the loser vanishes from the registry, and Meteor's own code looks modules up by
 * class without a null check, so a discarded Meteor module crashes the client. This addon lost
 * Meteor's {@code AutoReconnect} that way and threw on every disconnect, from
 * {@code DisconnectedScreenMixin}.
 *
 * <p>The check is kept separate from the registry so it can be exercised without a running game, and
 * so the startup guard and the {@code log modules check} command report from one implementation.
 */
public final class ModuleAudit {

    /** A name that is claimed more than once, with the number of claimants. */
    public record Clash(String name, int count) {
    }

    private ModuleAudit() {
    }

    /**
     * Returns every name that appears more than once, sorted by name.
     *
     * <p>Null and blank names are ignored: Meteor rejects those outright, so they are a different
     * problem and reporting them here would bury the clashes that actually delete a module.
     */
    public static List<Clash> clashes(Collection<String> names) {
        if (names == null) return List.of();

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            counts.merge(name, 1, Integer::sum);
        }

        Map<String, Integer> clashing = new TreeMap<>();
        counts.forEach((name, count) -> {
            if (count > 1) clashing.put(name, count);
        });

        List<Clash> out = new ArrayList<>(clashing.size());
        clashing.forEach((name, count) -> out.add(new Clash(name, count)));
        return List.copyOf(out);
    }

    /** True when {@link #clashes} would report nothing. */
    public static boolean isClean(Collection<String> names) {
        return clashes(names).isEmpty();
    }

    /** A one-line summary suitable for a chat reply. */
    public static String summary(Collection<String> names) {
        List<Clash> found = clashes(names);
        if (found.isEmpty()) return "No module name clashes.";
        StringBuilder sb = new StringBuilder();
        sb.append(found.size()).append(found.size() == 1 ? " clash: " : " clashes: ");
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(found.get(i).name()).append(" (x").append(found.get(i).count()).append(')');
        }
        return sb.toString();
    }
}
