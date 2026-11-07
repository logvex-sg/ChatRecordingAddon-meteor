package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a complete schematic material report into an ordered list of requirements.
 *
 * <p>Two input shapes are supported and auto-detected:
 *
 * <p><b>Format A — simplified Log Client list.</b> One entry per line, id first, quantity after:
 * <pre>
 *   minecraft:quartz_pillar 3861
 *   minecraft:spruce_planks 3420
 *   minecraft:polished_blackstone_brick_stairs 3191
 * </pre>
 *
 * <p><b>Format B — original material table.</b> Markdown-ish rows, optionally split into
 * sections. Only the {@code Material List} section is imported; {@code Raw Resources Required}
 * and {@code Choice Requirements} are ignored:
 * <pre>
 *   | Material | Quantity |
 *   | quartz pillar | 3861 (60 stacks + 21) |
 * </pre>
 *
 * <p>The whole file is processed until EOF: there is no line or entry cap, blank lines and
 * comments are skipped, quantities above one stack are kept, duplicate ids are aggregated onto
 * their first occurrence and the original order is preserved.
 */
public final class MaterialListImporter {
    public static final int DEFAULT_QUANTITY = 1;

    /** Result of parsing a file: the ordered requirements plus what was skipped. */
    public record Result(
        List<Requirement> requirements,
        List<InvalidEntry> invalid,
        int duplicateIdsMerged,
        long totalRequired
    ) {
        public boolean isEmpty() {
            return requirements.isEmpty();
        }

        /** Number of entries that were accepted, before duplicates were merged. */
        public int validEntries() {
            return requirements.size() + duplicateIdsMerged;
        }

        /** Total quantity after duplicates were merged. */
        public long totalQuantity() {
            return totalRequired;
        }

        /** Ordered preview for the import dialog, capped so the GUI stays responsive. */
        public List<Requirement> preview(int max) {
            if (max <= 0 || requirements.size() <= max) return List.copyOf(requirements);
            return List.copyOf(requirements.subList(0, max));
        }
    }

    /** One line that could not be imported, with the reason shown to the user. */
    public record InvalidEntry(int lineNumber, String raw, String reason) {
        @Override
        public String toString() {
            return "line " + lineNumber + ": " + reason;
        }
    }

    private MaterialListImporter() {
    }

    /** Parses with a syntax-only resolver; unknown ids are reported as invalid. */
    public static Result parse(List<String> lines) {
        return parse(lines, ItemResolver.lenient());
    }

    /** Parses with a registry-backed resolver. */
    public static Result parse(List<String> lines, ItemResolver resolver) {
        ItemResolver reg = resolver == null ? ItemResolver.lenient() : resolver;
        Map<String, Accumulator> ordered = new LinkedHashMap<>();
        List<InvalidEntry> invalid = new ArrayList<>();
        int merged = 0;

        if (lines == null) {
            return new Result(List.of(), List.of(), 0, 0);
        }

        boolean table = looksLikeTable(lines);
        Section section = Section.NONE;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null) continue;
            int lineNumber = i + 1;

            String line = stripBom(raw).trim();
            if (line.isEmpty()) continue;

            if (table) {
                // Section headings are matched before the comment check because reports commonly
                // write them as markdown headings, which would otherwise look like a comment.
                // A heading is never a data row: it is either a plain line or a single-cell table
                // row. Restricting the check this way stops a material such as "crafting table"
                // from being mistaken for the "Crafting" section heading.
                boolean dataRow = line.startsWith("|");
                boolean singleCell = dataRow && splitCells(line).size() <= 1;
                if (!dataRow || singleCell) {
                    SectionChange change = sectionChange(line);
                    if (change == SectionChange.START_MATERIALS) {
                        section = Section.MATERIALS;
                        continue;
                    }
                    if (change == SectionChange.OTHER_SECTION) {
                        section = Section.OTHER;
                        continue;
                    }
                }
            }

            if (isComment(line)) continue;

            if (table) {
                if (section == Section.OTHER) continue;
                if (isTableBorder(line)) continue;

                ParsedRow row = parseTableRow(line, reg);
                if (row == null) continue;
                if (row.invalid()) {
                    invalid.add(new InvalidEntry(lineNumber, line, row.reason()));
                    continue;
                }
                if (add(ordered, row.itemId(), row.displayName(), row.quantity())) merged++;
                continue;
            }

            ParsedRow row = parseSimpleRow(line, reg);
            if (row == null) continue;
            if (row.invalid()) {
                invalid.add(new InvalidEntry(lineNumber, line, row.reason()));
                continue;
            }
            if (add(ordered, row.itemId(), row.displayName(), row.quantity())) merged++;
        }

        List<Requirement> out = new ArrayList<>(ordered.size());
        long total = 0;
        for (Accumulator acc : ordered.values()) {
            out.add(new Requirement(acc.itemId, acc.displayName, acc.quantity));
            total += acc.quantity;
        }
        return new Result(List.copyOf(out), List.copyOf(invalid), merged, total);
    }

    /** Convenience: parses straight into a plan. */
    public static MaterialPlan parseToPlan(List<String> lines, ItemResolver resolver) {
        MaterialPlan plan = new MaterialPlan();
        plan.set(parse(lines, resolver).requirements());
        return plan;
    }

    // ------------------------------------------------------------------ formats

    /**
     * A file is a table when any content line starts with a pipe. The whole file is scanned
     * rather than just the first line because reports usually begin with a title or a comment.
     */
    private static boolean looksLikeTable(List<String> lines) {
        for (String raw : lines) {
            if (raw == null) continue;
            String line = stripBom(raw).trim();
            if (line.isEmpty() || isComment(line)) continue;
            if (line.startsWith("|")) return true;
        }
        return false;
    }

    private enum Section { NONE, MATERIALS, OTHER }

    private enum SectionChange { START_MATERIALS, OTHER_SECTION, NONE }

    private static SectionChange sectionChange(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        String cleaned = lower.replace("#", " ").replace("*", " ").replace("|", " ").trim();
        if (cleaned.isEmpty()) return SectionChange.NONE;

        if (cleaned.startsWith("material list") || cleaned.startsWith("materials list")
            || cleaned.startsWith("materials required") || cleaned.startsWith("material requirements")
            || cleaned.startsWith("blocks required") || cleaned.startsWith("blocks list")) {
            return SectionChange.START_MATERIALS;
        }
        if (cleaned.startsWith("raw resources") || cleaned.startsWith("raw resource")
            || cleaned.startsWith("choice requirements") || cleaned.startsWith("choices")
            || cleaned.startsWith("alternative") || cleaned.startsWith("crafting")
            || cleaned.startsWith("other requirements") || cleaned.startsWith("ingredients")) {
            return SectionChange.OTHER_SECTION;
        }
        return SectionChange.NONE;
    }

    private static boolean isTableBorder(String line) {
        // Separator rows such as |---|---| or +---+---+ contain no letters.
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isLetter(c)) return false;
        }
        return true;
    }

    /** A parsed row: either a valid entry or an explicitly invalid one. */
    private record ParsedRow(String itemId, String displayName, int quantity, boolean invalid, String reason) {
        static ParsedRow valid(String itemId, String displayName, int quantity) {
            return new ParsedRow(itemId, displayName, quantity, false, null);
        }

        static ParsedRow invalid(String reason) {
            return new ParsedRow(null, null, 0, true, reason);
        }
    }

    private static ParsedRow parseSimpleRow(String line, ItemResolver resolver) {
        String[] parts = line.split("\\s+");
        if (parts.length == 0) return null;

        String token = parts[0];
        String itemId = normaliseId(token);
        int quantity = DEFAULT_QUANTITY;
        StringBuilder name = new StringBuilder();

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            Integer q = parseQuantity(part);
            if (q != null && name.length() == 0 && quantity == DEFAULT_QUANTITY) {
                quantity = q;
            } else {
                if (name.length() > 0) name.append(' ');
                name.append(part);
            }
        }

        String display = name.length() > 0 ? name.toString() : null;
        if (!resolver.isValidId(itemId)) {
            return ParsedRow.invalid("unknown item id '" + token + "'");
        }
        return ParsedRow.valid(itemId, display, quantity);
    }

    private static ParsedRow parseTableRow(String line, ItemResolver resolver) {
        List<String> cells = splitCells(line);
        if (cells.isEmpty()) return null;

        String nameCell = cells.get(0);
        String quantityCell = cells.size() > 1 ? cells.get(1) : "";
        if (isHeaderRow(nameCell, quantityCell)) return null;

        String itemId = null;
        String display = nameCell;

        if (nameCell.contains(":")) {
            String candidate = normaliseId(nameCell);
            if (resolver.isValidId(candidate)) itemId = candidate;
        }
        if (itemId == null) {
            String resolved = resolver.resolveName(nameCell);
            if (resolved != null) {
                itemId = resolved;
                display = nameCell;
            } else {
                String fallback = normaliseId(nameCell.replace(' ', '_'));
                if (resolver.isValidId(fallback)) {
                    itemId = fallback;
                    display = nameCell;
                }
            }
        }
        if (itemId == null) {
            return ParsedRow.invalid("unknown material '" + nameCell + "'");
        }

        int quantity = parseTableQuantity(quantityCell);
        if (quantity <= 0) return ParsedRow.invalid("invalid quantity '" + quantityCell.trim() + "'");
        return ParsedRow.valid(itemId, display, quantity);
    }

    private static boolean isHeaderRow(String name, String quantity) {
        String a = name.toLowerCase(Locale.ROOT).trim();
        String b = quantity.toLowerCase(Locale.ROOT).trim();
        return a.equals("material") || a.equals("materials") || a.equals("item") || a.equals("block")
            || b.equals("quantity") || b.equals("amount") || b.equals("count") || b.equals("qty");
    }

    private static List<String> splitCells(String line) {
        List<String> cells = new ArrayList<>(4);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        // A leading or trailing pipe produces empty edge cells.
        cells.removeIf(String::isEmpty);
        return cells;
    }

    /**
     * Extracts the leading integer of a quantity cell. Handles thousands separators and ignores
     * the stack breakdown in parentheses, e.g. {@code 3861 (60 stacks + 21)}.
     */
    public static int parseTableQuantity(String cell) {
        if (cell == null) return -1;
        String text = cell.trim();
        if (text.isEmpty()) return -1;

        int end = 0;
        boolean sawDigit = false;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isDigit(c)) {
                sawDigit = true;
                end++;
            } else if (c == ',' || c == '.' || c == '\'' || c == ' ' || c == '\u00a0') {
                // Separators are allowed only between digits; stop at anything else.
                if (!sawDigit) break;
                end++;
            } else {
                break;
            }
        }
        if (!sawDigit) return -1;

        String digits = text.substring(0, end).replace(",", "").replace(".", "")
            .replace("'", "").replace(" ", "").replace("\u00a0", "");
        if (digits.isEmpty()) return -1;
        try {
            long value = Long.parseLong(digits);
            if (value <= 0) return -1;
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean add(Map<String, Accumulator> ordered, String itemId, String display, int quantity) {
        if (itemId == null || quantity <= 0) return false;
        Accumulator existing = ordered.get(itemId);
        if (existing == null) {
            ordered.put(itemId, new Accumulator(itemId, display, quantity));
            return false;
        }
        existing.quantity = saturatingAdd(existing.quantity, quantity);
        return true;
    }

    private static int saturatingAdd(int a, int b) {
        long sum = (long) a + b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static final class Accumulator {
        final String itemId;
        final String displayName;
        int quantity;

        Accumulator(String itemId, String displayName, int quantity) {
            this.itemId = itemId;
            this.displayName = displayName == null || displayName.isBlank()
                ? com.logvex.addon.common.Messages.prettyId(itemId) : displayName;
            this.quantity = quantity;
        }
    }

    // ------------------------------------------------------------------ helpers

    public static boolean isSyntacticallyValidId(String id) {
        if (id == null || id.isEmpty()) return false;
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) return false;
        if (id.indexOf(':', colon + 1) >= 0) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == ':' || c == '_' || c == '.' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) continue;
            return false;
        }
        return true;
    }

    static String normaliseId(String token) {
        if (token == null) return "";
        String id = token.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return id;
        if (!id.contains(":")) id = "minecraft:" + id;
        return id;
    }

    private static Integer parseQuantity(String token) {
        if (token == null) return null;
        String t = token;
        if (t.length() > 1 && (t.charAt(0) == 'x' || t.charAt(0) == 'X')) t = t.substring(1);
        if (t.isEmpty()) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ',' || c == '\'') continue;
            if (!Character.isDigit(c)) return null;
        }
        String digits = t.replace(",", "").replace("'", "");
        if (digits.isEmpty()) return null;
        try {
            long value = Long.parseLong(digits);
            if (value <= 0) return null;
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isComment(String line) {
        return line.startsWith("#") || line.startsWith("//");
    }

    private static String stripBom(String line) {
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') return line.substring(1);
        return line;
    }
}
