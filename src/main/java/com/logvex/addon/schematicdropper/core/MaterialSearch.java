package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filtering and sorting for the material dashboards.
 *
 * <p>Pure list transforms over {@link MaterialProgress}, so the GUI can stay a thin rendering layer
 * and the behaviour can be unit tested without a screen.
 */
public final class MaterialSearch {
    private MaterialSearch() {
    }

    /** Which materials a filter admits. */
    public enum Filter {
        ALL,
        MISSING,
        COMPLETE,
        INCOMPLETE,
        IN_INVENTORY,
        NOT_IN_INVENTORY;

        @Override
        public String toString() {
            return switch (this) {
                case ALL -> "All";
                case MISSING -> "Missing";
                case COMPLETE -> "Complete";
                case INCOMPLETE -> "Incomplete";
                case IN_INVENTORY -> "In Inventory";
                case NOT_IN_INVENTORY -> "Not In Inventory";
            };
        }

        public Filter next() {
            Filter[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /** How a filtered list is ordered. */
    public enum Sort {
        NAME,
        REQUIRED_DESC,
        REMAINING_DESC,
        STATUS;

        @Override
        public String toString() {
            return switch (this) {
                case NAME -> "Name";
                case REQUIRED_DESC -> "Required";
                case REMAINING_DESC -> "Remaining";
                case STATUS -> "Status";
            };
        }

        public Sort next() {
            Sort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /**
     * Applies a text query (matched against the id and display name, case-insensitively), a
     * {@link Filter} and a {@link Sort}.
     */
    public static List<MaterialProgress> apply(List<MaterialProgress> input, String query, Filter filter, Sort sort) {
        List<MaterialProgress> out = new ArrayList<>();
        if (input == null) return out;

        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (MaterialProgress m : input) {
            if (m == null) continue;
            if (!needle.isEmpty() && !matches(m, needle)) continue;
            if (!admits(m, filter)) continue;
            out.add(m);
        }

        switch (sort == null ? Sort.STATUS : sort) {
            case NAME -> out.sort((a, b) -> {
                int c = String.CASE_INSENSITIVE_ORDER.compare(a.displayName(), b.displayName());
                return c != 0 ? c : a.itemId().compareTo(b.itemId());
            });
            case REQUIRED_DESC -> out.sort((a, b) -> Integer.compare(b.required(), a.required()));
            case REMAINING_DESC -> out.sort((a, b) -> Integer.compare(b.remaining(), a.remaining()));
            case STATUS -> out.sort((a, b) -> {
                int c = Boolean.compare(a.complete(), b.complete());
                if (c != 0) return c;
                c = Integer.compare(b.remaining(), a.remaining());
                if (c != 0) return c;
                return String.CASE_INSENSITIVE_ORDER.compare(a.displayName(), b.displayName());
            });
        }

        return out;
    }

    private static boolean matches(MaterialProgress m, String needle) {
        return m.itemId().toLowerCase(Locale.ROOT).contains(needle)
            || m.displayName().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean admits(MaterialProgress m, Filter filter) {
        if (filter == null) return true;
        return switch (filter) {
            case ALL -> true;
            case MISSING -> m.missing() > 0;
            case COMPLETE -> m.complete();
            case INCOMPLETE -> !m.complete();
            case IN_INVENTORY -> m.inventory() > 0;
            case NOT_IN_INVENTORY -> m.inventory() <= 0;
        };
    }
}
