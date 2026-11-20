package com.logvex.addon.chatlog;

import com.logvex.addon.chatlog.core.SummaryCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-game overview card printed by {@code log summary}.
 *
 * <p>Unlike the Discord digest this is read in chat, where an empty heading or a dangling
 * separator looks like a bug, so the layout rules are pinned: blank figures vanish, a section with
 * nothing to say vanishes, and the section order is fixed.
 */
class SummaryCardTest {

    private static SummaryCard.Inputs full() {
        return new SummaryCard.Inputs(
            "12m", "4.2 / min", "1.2 MB", "3 online", "41 seen",
            "2 keyword", "1 webhook", "18 places", "idle", "off");
    }

    @Test
    @DisplayName("a full card keeps every section in reading order")
    void fullCardKeepsOrder() {
        List<SummaryCard.Section> sections = SummaryCard.sections(full());
        assertEquals(List.of("Session", "Players", "Alerts", "World", "Automation"),
            sections.stream().map(SummaryCard.Section::title).toList());
    }

    @Test
    @DisplayName("a blank figure is dropped instead of printing an empty row")
    void blankRowsAreDropped() {
        SummaryCard.Inputs inputs = new SummaryCard.Inputs(
            "12m", "", "1.2 MB", null, "41 seen", "", "", "", "idle", "");
        List<SummaryCard.Section> sections = SummaryCard.sections(inputs);

        assertEquals(List.of("12m", "1.2 MB"), sections.get(0).rows());
        assertEquals(List.of("41 seen"), sections.get(1).rows());
    }

    @Test
    @DisplayName("a section with nothing to say disappears entirely")
    void emptySectionsDisappear() {
        SummaryCard.Inputs inputs = new SummaryCard.Inputs(
            "12m", null, null, null, null, null, null, null, null, null);
        List<SummaryCard.Section> sections = SummaryCard.sections(inputs);
        assertEquals(1, sections.size());
        assertEquals("Session", sections.get(0).title());
    }

    @Test
    @DisplayName("an empty or absent input renders no card at all")
    void nothingRendersNothing() {
        assertTrue(SummaryCard.sections(null).isEmpty());
        assertTrue(SummaryCard.sections(new SummaryCard.Inputs(
            null, null, null, null, null, null, null, null, null, null)).isEmpty());
    }

    @Test
    @DisplayName("only the first row of a section carries the label, so the block stays aligned")
    void onlyFirstRowIsLabelled() {
        List<SummaryCard.FieldPair> fields = SummaryCard.fields(SummaryCard.sections(full()));
        assertEquals("Session", fields.get(0).label());
        assertEquals("12m", fields.get(0).value());
        assertEquals("", fields.get(1).label());
        assertEquals("4.2 / min", fields.get(1).value());
        assertEquals("", fields.get(2).label());
    }

    @Test
    @DisplayName("join skips blanks so a value never reads with a dangling separator")
    void joinSkipsBlanks() {
        assertEquals("2 keyword, 1 webhook", SummaryCard.join(", ", "2 keyword", "", null, "1 webhook"));
        assertEquals("", SummaryCard.join(", ", "", null));
    }

    @Test
    @DisplayName("every row of every section reaches the rendered fields")
    void everyRowSurvivesRendering() {
        List<SummaryCard.Section> sections = SummaryCard.sections(full());
        int rows = sections.stream().mapToInt(s -> s.rows().size()).sum();
        assertEquals(rows, SummaryCard.fields(sections).size());
    }
}
