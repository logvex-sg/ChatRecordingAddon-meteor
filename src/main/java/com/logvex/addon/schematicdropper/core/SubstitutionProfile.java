package com.logvex.addon.schematicdropper.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named set of material substitutions, e.g. {@code minecraft:oak_planks -> minecraft:spruce_planks}.
 *
 * <p>Profiles let a user build a schematic from materials they actually have. Substitutions are
 * applied to a plan, never to the schematic itself, so the original requirements stay intact for
 * reporting.
 */
public final class SubstitutionProfile {
    private final String name;
    private final Map<String, String> substitutions;

    public SubstitutionProfile(String name, Map<String, String> substitutions) {
        this.name = name == null || name.isBlank() ? "default" : name;
        this.substitutions = new LinkedHashMap<>(substitutions == null ? Map.of() : substitutions);
    }

    public static SubstitutionProfile empty(String name) {
        return new SubstitutionProfile(name, Map.of());
    }

    public String name() {
        return name;
    }

    public Map<String, String> substitutions() {
        return Map.copyOf(substitutions);
    }

    public boolean isEmpty() {
        return substitutions.isEmpty();
    }

    /** The replacement for {@code itemId}, or the id itself when there is no rule. */
    public String resolve(String itemId) {
        String replacement = substitutions.get(itemId);
        return replacement == null || replacement.isBlank() ? itemId : replacement;
    }

    public SubstitutionProfile with(String from, String to) {
        Map<String, String> next = new LinkedHashMap<>(substitutions);
        next.put(from, to);
        return new SubstitutionProfile(name, next);
    }

    public SubstitutionProfile without(String from) {
        Map<String, String> next = new LinkedHashMap<>(substitutions);
        next.remove(from);
        return new SubstitutionProfile(name, next);
    }

    /** Rewrites a plan's requirements, aggregating quantities of items that map to the same target. */
    public MaterialPlan apply(MaterialPlan plan) {
        if (plan == null || substitutions.isEmpty()) return plan;
        Map<String, Requirement> merged = new LinkedHashMap<>();
        for (Requirement r : plan.requirements()) {
            String target = resolve(r.itemId());
            Requirement existing = merged.get(target);
            if (existing == null) {
                merged.put(target, new Requirement(target, com.logvex.addon.common.Messages.prettyId(target), r.required()));
            } else {
                merged.put(target, new Requirement(target, existing.displayName(),
                    saturatingAdd(existing.required(), r.required())));
            }
        }
        MaterialPlan out = new MaterialPlan();
        out.set(merged.values());
        return out;
    }

    private static int saturatingAdd(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
