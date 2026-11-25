package com.logvex.addon.chatlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screens were rewritten to use aligned fact tables and stat tiles instead of stacking one
 * key/value row per line. Those two helpers are package-private in the GUI package, so the shape of
 * the fix is checked at the source level: a regression here is somebody reintroducing the stacked
 * form, which is exactly the thing that made the panels hard to read.
 */
class ScreenFormattingTest {

    private static final Path GUI = Path.of("src/main/java/com/logvex/addon/chatlog/gui");

    /** Every screen that shows a list of facts. */
    private static List<Path> screens() throws IOException {
        try (Stream<Path> files = Files.list(GUI)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    @DisplayName("no screen stacks a new list per key/value row")
    void noStackedKeyValueRows() throws IOException {
        for (Path screen : screens()) {
            String source = Files.readString(screen);
            assertFalse(source.contains("GuiKit.keyValue"),
                screen.getFileName() + " still uses the unnamed key/value helper");
            assertFalse(source.contains("keyValue(add(theme.verticalList())"),
                screen.getFileName() + " builds one list per fact, so the values cannot line up");
        }
    }

    @Test
    @DisplayName("the fact and tile helpers are the only way those rows are built")
    void screensUseSharedHelpers() throws IOException {
        int facts = 0;
        int tiles = 0;
        for (Path screen : screens()) {
            String source = Files.readString(screen);
            facts += count(source, "GuiKit.facts(");
            tiles += count(source, "GuiKit.tiles(");
        }
        assertTrue(facts >= 6, "expected the fact helper to be used across the screens, saw " + facts);
        assertTrue(tiles >= 3, "expected the tile helper to be used across the screens, saw " + tiles);
    }

    @Test
    @DisplayName("screens take their colours from the theme, not a hardcoded palette")
    void coloursComeFromTheTheme() throws IOException {
        for (Path screen : screens()) {
            String source = Files.readString(screen);
            for (String stale : List.of("GuiKit.MUTED", "GuiKit.ACCENT", "GuiKit.GOOD",
                "GuiKit.WARN", "GuiKit.BAD")) {
                assertFalse(source.contains(stale),
                    screen.getFileName() + " uses " + stale
                        + ", which ignores the active theme preset");
            }
        }
    }

    @Test
    @DisplayName("counts are grouped and sizes carry units")
    void numbersAreFormatted() throws IOException {
        String storage = Files.readString(GUI.resolve("StorageScreen.java"));
        assertTrue(storage.contains("Messages.number("), "segment counts must be grouped");
        assertTrue(storage.contains("Messages.bytes("), "sizes must use the shared formatter");
        assertTrue(storage.contains("limitOrUnlimited("), "a zero limit must read as unlimited");
        // A retention limit without its unit reads as a bare number the player has to interpret.
        assertTrue(storage.contains("limitOrUnlimited(logger.maxAgeDays(), \"days\")"),
            "the age limit must say what it counts");
    }

    @Test
    @DisplayName("the GUI has no second copy of the truncate rule")
    void truncateIsNotDuplicated() throws IOException {
        String kit = Files.readString(GUI.resolve("GuiKit.java"));
        assertFalse(kit.contains("substring(0, max - 1)"),
            "GuiKit grew its own truncate again: that copy had no guard, so a max of 0 or less"
                + " threw instead of returning an empty string. Delegate to Messages.truncate.");
        assertTrue(kit.contains("Messages.truncate("),
            "GuiKit.truncate must delegate to the one guarded implementation");
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            total++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return total;
    }
}
