package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ReactionCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways a category name is resolved, and why they must stay different.
 *
 * <p>A stored id has to fall back so an old config keeps loading. A name typed into a command must
 * not: silently resolving a typo to CUSTOM would switch off the user's own rules while reporting
 * success, which is the confusion the {@code ai reactions} command exists to remove.
 */
class ReactionCategoryLookupTest {

    @Test
    @DisplayName("a typed name resolves by id, case-insensitively")
    void exactLookupAcceptsIdsInAnyCase() {
        assertEquals(ReactionCategory.GREETINGS, ReactionCategory.exact("greetings"));
        assertEquals(ReactionCategory.GREETINGS, ReactionCategory.exact("GREETINGS"));
        assertEquals(ReactionCategory.GREETINGS, ReactionCategory.exact("  Greetings  "));
    }

    @Test
    @DisplayName("a typed enum name also resolves, so either spelling works")
    void exactLookupAcceptsEnumNames() {
        assertEquals(ReactionCategory.SOCIAL, ReactionCategory.exact("SOCIAL"));
        assertEquals(ReactionCategory.SOCIAL, ReactionCategory.exact("social"));
    }

    @Test
    @DisplayName("a typo returns null instead of silently meaning CUSTOM")
    void aTypoDoesNotFallBackToCustom() {
        assertNull(ReactionCategory.exact("greetigns"));
        assertNull(ReactionCategory.exact("nonsense"));
        assertNull(ReactionCategory.exact(""));
        assertNull(ReactionCategory.exact(null));
    }

    @Test
    @DisplayName("parse still falls back, because stored ids must keep loading")
    void parseKeepsItsFallback() {
        assertEquals(ReactionCategory.CUSTOM, ReactionCategory.parse("a-category-from-the-future"));
        assertEquals(ReactionCategory.CUSTOM, ReactionCategory.parse(null));
        assertEquals(ReactionCategory.FAREWELLS, ReactionCategory.parse("farewells"));
    }

    @Test
    @DisplayName("every category id is unique, so a lookup can never be ambiguous")
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ReactionCategory category : ReactionCategory.all()) {
            assertTrue(seen.add(category.id()), "duplicate id: " + category.id());
        }
    }

    @Test
    @DisplayName("the error message lists every category the user can type")
    void theIdListCoversEveryCategory() {
        String list = ReactionCategory.idList();
        for (ReactionCategory category : ReactionCategory.all()) {
            assertTrue(list.contains(category.id()), "missing from id list: " + category.id());
        }
        // Each id has to be resolvable, or the message would suggest a name that does not work.
        for (ReactionCategory category : ReactionCategory.all()) {
            assertNotNull(ReactionCategory.exact(category.id()));
        }
    }

    @Test
    @DisplayName("the conversational categories stay off by default")
    void chattyCategoriesRemainOptIn() {
        Set<ReactionCategory> defaults = ReactionCategory.defaultsEnabled();
        for (ReactionCategory category : ReactionCategory.all()) {
            if (category.conversational()) {
                assertTrue(!defaults.contains(category),
                    category.id() + " is conversational and must be off by default");
            }
        }
        // Commands and custom rules are explicit instructions, so they stay on.
        assertTrue(defaults.contains(ReactionCategory.COMMANDS));
        assertTrue(defaults.contains(ReactionCategory.CUSTOM));
    }
}
