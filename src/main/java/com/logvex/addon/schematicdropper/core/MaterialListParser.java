package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an imported material list.
 *
 * <p>Accepted line formats (blank lines and {@code #} comments are ignored):
 * <pre>
 *   minecraft:cobblestone 128
 *   minecraft:cobblestone x128
 *   minecraft:cobblestone 128 Cobblestone
 *   minecraft:cobblestone
 * </pre>
 * A line without a quantity defaults to {@link #DEFAULT_QUANTITY}.
 */
public final class MaterialListParser {
    public static final int DEFAULT_QUANTITY = 64;

    private MaterialListParser() {
    }

    /** @return ordered requirements; invalid lines are skipped. */
    public static List<Requirement> parse(List<String> lines) {
        Map<String, Requirement> out = new LinkedHashMap<>();
        if (lines == null) return new ArrayList<>();

        for (String raw : lines) {
            Requirement r = parseLine(raw);
            if (r != null) out.put(r.itemId(), r);
        }

        return new ArrayList<>(out.values());
    }

    public static MaterialPlan parseToPlan(List<String> lines) {
        MaterialPlan plan = new MaterialPlan();
        plan.set(parse(lines));
        return plan;
    }

    /** @return the parsed requirement, or {@code null} when the line is blank, a comment or invalid. */
    public static Requirement parseLine(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) return null;

        String[] parts = line.split("\\s+");
        String itemId = normaliseId(parts[0]);
        if (!isValidId(itemId)) return null;

        int quantity = DEFAULT_QUANTITY;
        StringBuilder name = new StringBuilder();

        for (int i = 1; i < parts.length; i++) {
            String token = parts[i];
            Integer q = parseQuantity(token);
            if (q != null && name.length() == 0) {
                quantity = q;
            } else {
                if (name.length() > 0) name.append(' ');
                name.append(token);
            }
        }

        String display = name.length() > 0 ? name.toString() : prettyName(itemId);
        return new Requirement(itemId, display, quantity);
    }

    private static Integer parseQuantity(String token) {
        String t = token;
        if (t.length() > 1 && (t.charAt(0) == 'x' || t.charAt(0) == 'X')) t = t.substring(1);
        if (t.isEmpty()) return null;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) return null;
        }
        try {
            int v = Integer.parseInt(t);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normaliseId(String token) {
        String id = token.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty()) return id;
        if (!id.contains(":")) id = "minecraft:" + id;
        return id;
    }

    private static boolean isValidId(String id) {
        if (id == null || id.isEmpty()) return false;
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) return false;
        if (id.indexOf(':', colon + 1) >= 0) return false; // exactly one namespace separator
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == ':' || c == '_' || c == '.' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) continue;
            return false;
        }
        return true;
    }

    private static String prettyName(String itemId) {
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String[] words = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
