package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Turns a plan into a stable processing order. */
public final class MaterialOrder {
    private MaterialOrder() {
    }

    public static List<Requirement> order(MaterialPlan plan, OrderingMode mode) {
        List<Requirement> list = new ArrayList<>(plan.requirements());
        if (list.isEmpty()) return list;

        Comparator<Requirement> byName =
            Comparator.comparing(Requirement::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Requirement::itemId);

        switch (mode == null ? OrderingMode.SCHEMATIC_ORDER : mode) {
            case SCHEMATIC_ORDER -> { /* keep insertion order */ }
            case ALPHABETICAL -> list.sort(byName);
            case QUANTITY_DESCENDING -> list.sort(byName.thenComparing(Comparator.comparingInt(Requirement::required).reversed()));
            case QUANTITY_ASCENDING -> list.sort(byName.thenComparingInt(Requirement::required));
        }

        return list;
    }
}
