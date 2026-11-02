package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered set of schematic material requirements. Pure data holder: it never touches the
 * Minecraft runtime, so it can be unit tested directly.
 */
public final class MaterialPlan {
    private final LinkedHashMap<String, Requirement> byId = new LinkedHashMap<>();

    /** Replaces the whole plan. Requirements with a non-positive quantity are ignored. */
    public void set(Collection<Requirement> requirements) {
        byId.clear();
        if (requirements == null) return;
        for (Requirement r : requirements) {
            if (r != null && r.required() > 0) byId.put(r.itemId(), r);
        }
    }

    public void add(Requirement requirement) {
        if (requirement != null && requirement.required() > 0) byId.put(requirement.itemId(), requirement);
    }

    public boolean contains(String itemId) {
        return byId.containsKey(itemId);
    }

    public Requirement get(String itemId) {
        return byId.get(itemId);
    }

    public List<Requirement> requirements() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public int size() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public void clear() {
        byId.clear();
    }

    /** Sum of every required quantity. Zero when the plan is empty. */
    public long totalRequired() {
        long total = 0;
        for (Requirement r : byId.values()) total += r.required();
        return total;
    }

    public Map<String, Requirement> asMap() {
        return Collections.unmodifiableMap(byId);
    }
}
