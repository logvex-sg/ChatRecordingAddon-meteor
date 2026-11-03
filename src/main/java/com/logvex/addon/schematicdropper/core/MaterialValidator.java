package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a parsed material list against the item registry and reports every problem with the
 * line it came from.
 *
 * <p>The importer already rejects entries it cannot parse; this pass is the user-facing audit that
 * runs after an import and explains, per line, what was wrong. It never mutates the plan.
 */
public final class MaterialValidator {
    private final ItemResolver resolver;

    public MaterialValidator(ItemResolver resolver) {
        this.resolver = resolver == null ? ItemResolver.lenient() : resolver;
    }

    /** One problem found while validating. */
    public record Issue(int lineNumber, String entry, String reason) {
        @Override
        public String toString() {
            return "line " + lineNumber + ": " + reason + " (" + entry + ")";
        }
    }

    /** The outcome of validating an imported list. */
    public record Report(List<Requirement> valid, List<Issue> invalid, long totalQuantity) {
        public boolean isEmpty() {
            return valid.isEmpty();
        }

        public int validCount() {
            return valid.size();
        }

        public int invalidCount() {
            return invalid.size();
        }

        public boolean hasProblems() {
            return !invalid.isEmpty();
        }
    }

    /**
     * Validates the requirements of a parsed result, keeping the original order.
     *
     * @param source the parsed result, whose {@code invalid} entries are merged into the report
     */
    public Report validate(MaterialListImporter.Result source) {
        List<Requirement> valid = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        long total = 0;

        if (source == null) return new Report(List.of(), List.of(), 0);

        for (MaterialListImporter.InvalidEntry entry : source.invalid()) {
            issues.add(new Issue(entry.lineNumber(), entry.raw(), entry.reason()));
        }

        for (Requirement r : source.requirements()) {
            if (r.itemId() == null || r.itemId().isBlank()) {
                issues.add(new Issue(0, "", "missing item id"));
                continue;
            }
            if (r.required() <= 0) {
                issues.add(new Issue(0, r.itemId(), "quantity must be greater than zero"));
                continue;
            }
            if (!resolver.isValidId(r.itemId())) {
                issues.add(new Issue(0, r.itemId(), "unknown item id '" + r.itemId() + "'"));
                continue;
            }
            valid.add(r);
            total += r.required();
        }

        return new Report(List.copyOf(valid), List.copyOf(issues), total);
    }

    /** Validates a plain list of requirements. */
    public Report validate(List<Requirement> requirements) {
        List<Requirement> valid = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        long total = 0;
        if (requirements != null) {
            for (Requirement r : requirements) {
                if (r == null) continue;
                if (!resolver.isValidId(r.itemId())) {
                    issues.add(new Issue(0, r.itemId(), "unknown item id '" + r.itemId() + "'"));
                    continue;
                }
                if (r.required() <= 0) {
                    issues.add(new Issue(0, r.itemId(), "quantity must be greater than zero"));
                    continue;
                }
                valid.add(r);
                total += r.required();
            }
        }
        return new Report(List.copyOf(valid), List.copyOf(issues), total);
    }
}
