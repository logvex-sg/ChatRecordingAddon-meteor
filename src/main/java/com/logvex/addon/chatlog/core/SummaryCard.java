package com.logvex.addon.chatlog.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the one-card overview printed by {@code log summary}.
 *
 * <p>Every figure already had its own command, so answering "what has happened?" meant running five
 * of them and stitching the answers together in your head. This gathers the same numbers into one
 * card whose sections read in the order a player asks about them: how long this session has run,
 * who is around, what was flagged, what was discovered, and what the automation is doing.
 *
 * <p>Minecraft-free and given plain inputs, so the layout, the section order and the "nothing here"
 * behaviour are all asserted in a unit test rather than discovered in a running client.
 */
public final class SummaryCard {

    /** One titled section of the card. Empty sections are dropped by {@link #sections()}. */
    public record Section(String title, List<String> rows) {
        public Section {
            if (title == null) title = "";
            if (rows == null) rows = List.of();
            rows = List.copyOf(rows);
        }
    }

    /** Plain figures the card is built from. A null or blank field is simply omitted. */
    public record Inputs(
        String sessionDuration,
        String messageRate,
        String storage,
        String playersOnline,
        String playersSeen,
        String keywordAlerts,
        String otherAlerts,
        String placesKnown,
        String automation,
        String companion
    ) {
    }

    private SummaryCard() {
    }

    /**
     * Renders {@code inputs} as ordered sections, dropping any that has no rows.
     *
     * <p>A section whose rows are all blank is dropped too: a heading followed by nothing reads as
     * a bug, and the card exists to be read at a glance.
     */
    public static List<Section> sections(Inputs inputs) {
        List<Section> out = new ArrayList<>(5);
        if (inputs == null) return out;

        add(out, "Session", inputs.sessionDuration(), inputs.messageRate(), inputs.storage());
        add(out, "Players", inputs.playersOnline(), inputs.playersSeen());
        add(out, "Alerts", inputs.keywordAlerts(), inputs.otherAlerts());
        add(out, "World", inputs.placesKnown());
        add(out, "Automation", inputs.automation(), inputs.companion());
        return out;
    }

    private static void add(List<Section> out, String title, String... rows) {
        List<String> kept = new ArrayList<>(rows.length);
        for (String row : rows) {
            if (row != null && !row.isBlank()) kept.add(row.strip());
        }
        if (!kept.isEmpty()) out.add(new Section(title, kept));
    }

    /**
     * Flattens the sections into the {@code label} / {@code value} pairs the reply block renders.
     *
     * <p>The first row of a section is labelled with the section name and the rest are indented
     * under it, so the block keeps its alignment without inventing a heading per line.
     */
    public static List<FieldPair> fields(List<Section> sections) {
        List<FieldPair> out = new ArrayList<>();
        if (sections == null) return out;
        for (Section section : sections) {
            for (int i = 0; i < section.rows().size(); i++) {
                String label = i == 0 ? section.title() : "";
                out.add(new FieldPair(label, section.rows().get(i)));
            }
        }
        return out;
    }

    /** A label and its value, before it becomes a reply field. */
    public record FieldPair(String label, String value) {
        public FieldPair {
            if (label == null) label = "";
            if (value == null) value = "";
        }
    }

    /** Joins {@code parts} with {@code separator}, skipping blanks, for one-line values. */
    public static String join(String separator, String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (out.length() > 0) out.append(separator);
            out.append(part.strip());
        }
        return out.toString();
    }
}
