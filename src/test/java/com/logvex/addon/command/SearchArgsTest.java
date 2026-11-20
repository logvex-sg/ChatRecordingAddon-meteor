package com.logvex.addon.command;

import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchArgsTest {

    @Test
    @DisplayName("a bare phrase becomes free text and keeps its words in order")
    void parsesFreeText() {
        SearchArgs.Parsed parsed = SearchArgs.parse("where is the base");
        assertEquals("where is the base", parsed.query().text());
        assertNull(parsed.query().player());
    }

    @Test
    @DisplayName("a quoted phrase stays one search term")
    void keepsQuotedPhrasesTogether() {
        SearchArgs.Parsed parsed = SearchArgs.parse("\"the base\" x");
        // Quotes are stripped, but the inner spaces survive so the phrase still matches as typed.
        assertEquals("the base x", parsed.query().text());
    }

    @Test
    @DisplayName("player: filters the sender instead of searching the text")
    void parsesPlayerFilter() {
        SearchArgs.Parsed parsed = SearchArgs.parse("player:alice diamonds");
        assertEquals("alice", parsed.query().player());
        assertEquals("diamonds", parsed.query().text());
    }

    @Test
    @DisplayName("type: accepts a list and ignores names it does not know")
    void parsesTypes() {
        Set<MessageType> types = SearchArgs.types("chat,death,nonsense");
        assertEquals(2, types.size());
        assertTrue(types.contains(MessageType.PUBLIC_CHAT));
        assertTrue(types.contains(MessageType.DEATH));
        assertFalse(types.contains(MessageType.OTHER));
    }

    @Test
    @DisplayName("an unknown type yields an empty filter, never a catch-all")
    void unknownTypeIsNotOther() {
        // Treating an unknown name as OTHER would silently return every uncategorised message,
        // which reads as "the filter was ignored" instead of "nothing matches".
        assertTrue(SearchArgs.types("gibberish").isEmpty());
        assertEquals(Set.of(MessageType.OTHER), SearchArgs.types("other"));
    }

    @Test
    @DisplayName("limit: is clamped and a bad value falls back to the default")
    void parsesLimit() {
        assertEquals(5, SearchArgs.parse("x limit:5").limit());
        assertEquals(10, SearchArgs.parse("x").limit());
        assertEquals(10, SearchArgs.parse("x limit:0").limit());
        assertEquals(10, SearchArgs.parse("x limit:abc").limit());
        assertEquals(50, SearchArgs.parse("x limit:999").limit());
    }

    @Test
    @DisplayName("an unknown key is searched for as text rather than rejected")
    void unknownKeyStaysText() {
        // Chat can contain a colon, and dropping the whole search over one would be worse than
        // looking for the term literally.
        SearchArgs.Parsed parsed = SearchArgs.parse("time:12:30 hello");
        assertEquals("time:12:30 hello", parsed.query().text());
        assertNull(parsed.query().player());
    }

    @Test
    @DisplayName("an empty key value is left as text, not applied as a blank filter")
    void emptyValueStaysText() {
        SearchArgs.Parsed parsed = SearchArgs.parse("player:");
        assertNull(parsed.query().player());
        assertEquals("player:", parsed.query().text());
    }

    @Test
    @DisplayName("filters combine with free text in one line")
    void combinesFilters() {
        SearchArgs.Parsed parsed = SearchArgs.parse("player:alice type:chat limit:3 found diamonds");
        assertEquals("alice", parsed.query().player());
        assertEquals(3, parsed.limit());
        assertEquals("found diamonds", parsed.query().text());
    }

    @Test
    @DisplayName("an empty line builds an unrestricted query instead of throwing")
    void handlesEmptyInput() {
        SearchArgs.Parsed parsed = SearchArgs.parse("   ");
        assertNull(parsed.query().text());
        assertNull(parsed.query().player());
        assertEquals(10, parsed.limit());
    }

    @Test
    @DisplayName("the parsed query actually matches what the user asked for")
    void queryMatchesEntries() {
        SearchArgs.Parsed parsed = SearchArgs.parse("player:alice diamonds");
        var match = new com.logvex.addon.chatlog.core.ChatEntry(
            1_000L, MessageType.PUBLIC_CHAT, "Alice", "found diamonds here", "srv", "s1");
        var wrongSender = new com.logvex.addon.chatlog.core.ChatEntry(
            1_000L, MessageType.PUBLIC_CHAT, "Bob", "found diamonds here", "srv", "s1");
        var wrongText = new com.logvex.addon.chatlog.core.ChatEntry(
            1_000L, MessageType.PUBLIC_CHAT, "Alice", "nothing useful", "srv", "s1");

        LogQuery query = parsed.query();
        assertTrue(query.matches(match));
        assertFalse(query.matches(wrongSender));
        assertFalse(query.matches(wrongText));
    }
}
