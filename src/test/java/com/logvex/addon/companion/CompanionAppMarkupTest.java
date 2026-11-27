package com.logvex.addon.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The app's own source, checked for the mistakes that a browser cannot show.
 *
 * <p>These are not tests of behaviour: they read {@code companion/app.html} and look for the
 * handful of things that are invisible when wrong. A radar dot placed by a flipped axis still
 * lands inside the circle, and a tab that exists in the rail but not in the page opens onto
 * nothing. Both look fine until a user tries them, so they are pinned here.
 */
class CompanionAppMarkupTest {

    private String html() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/companion/app.html")) {
            assertNotNull(in, "companion/app.html must ship in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("every tab in the rail has a section, and every section a tab")
    void tabsAndSectionsMatch() throws Exception {
        String html = html();
        Matcher tabsBlock = Pattern.compile("const TABS = \\[(.*?)\\n\\];", Pattern.DOTALL)
            .matcher(html);
        assertTrue(tabsBlock.find(), "the TABS declaration must be present");

        // A tab is ["id", "Label"] or ["id", "Label", "Group"].
        List<String> tabIds = new ArrayList<>();
        Matcher tabs = Pattern.compile("\\[\"([a-z-]+)\", \"[^\"]+\"(?:, \"[^\"]+\")?\\]")
            .matcher(tabsBlock.group(1));
        while (tabs.find()) tabIds.add(tabs.group(1));

        List<String> sectionIds = new ArrayList<>();
        Matcher sections = Pattern.compile("id=\"view-([a-z-]+)\"").matcher(html);
        while (sections.find()) sectionIds.add(sections.group(1));

        assertFalse(tabIds.isEmpty(), "the tab list must parse");
        for (String id : tabIds) {
            assertTrue(sectionIds.contains(id), "tab '" + id + "' has no <section id=\"view-" + id + "\">");
        }
        for (String id : sectionIds) {
            assertTrue(tabIds.contains(id), "section view-" + id + " is unreachable from a tab");
        }
    }

    @Test
    @DisplayName("each new view has a builder and the tab that reaches it")
    void newViewsAreWired() throws Exception {
        String html = html();
        for (String view : new String[]{"prism", "radar", "network", "appearance"}) {
            assertTrue(html.contains("[\"" + view + "\""),
                "the '" + view + "' tab must be declared in TABS");
            assertTrue(html.contains("id=\"view-" + view + "\""),
                "the '" + view + "' tab needs a section");
            assertTrue(html.contains(view + ": render"),
                "the '" + view + "' tab needs an entry in the builders map, or it opens blank");
        }
    }

    @Test
    @DisplayName("the radar turns by the player's yaw and by a drag, and not by one alone")
    void radarRotation() throws Exception {
        String html = html();
        // Follow-facing must include the half turn that reconciles a Minecraft yaw with a compass
        // canvas, and the drag offset must be added rather than stored and forgotten.
        Matcher rotation = Pattern.compile(
            "const rotation = \\(radarState\\.northUp \\? 0 : -?\\(?me\\.yaw[^;]*?- Math\\.PI\\)[^;]*?;")
            .matcher(html);
        assertTrue(rotation.find(), "follow-facing must rotate by the yaw plus a half turn");
        assertTrue(html.contains("radarState.yaw || 0"),
            "a drag must contribute its own offset to the rotation");
    }

    @Test
    @DisplayName("a bearing is named with a bearing table, not the yaw table")
    void bearingUsesItsOwnNames() throws Exception {
        String html = html();
        // compass() takes a Minecraft yaw, whose 0 is south. Feeding it a true bearing labelled
        // north-east as south-west, which is the kind of error nobody spots on a small dot.
        assertTrue(html.contains("function bearingName("),
            "a true bearing needs its own name table");
        assertTrue(html.contains("bearingName(entity.bearing)"),
            "the radar table must label a bearing with the bearing table");
    }

    @Test
    @DisplayName("the inventory maps a slot index onto the handler index the server expects")
    void inventorySlotMapping() throws Exception {
        String html = html();
        // The hotbar is 0-8 in the inventory view but 36-44 in the handler, so a click sent with
        // the raw index would act on the wrong stack.
        assertTrue(html.contains("function handlerSlotFor("),
            "the app must translate an inventory slot to a handler slot");
        assertTrue(html.contains("36"), "the hotbar offset of 36 must appear in that translation");
        assertTrue(html.contains("function mcSlot("), "slots must be drawn as Minecraft slots");
    }

    @Test
    @DisplayName("the shell is a sidebar and can be collapsed, and the choice is remembered")
    void shellLayout() throws Exception {
        String html = html();
        assertTrue(html.contains("class=\"shell\""), "the page must use the sidebar shell");
        assertTrue(html.contains("id=\"btn-rail\""), "the rail needs its own toggle");
        assertTrue(html.contains("rail-hidden"), "the collapsed rail needs a class to switch");
        assertTrue(html.contains("RAIL_KEY"),
            "the rail state must be remembered, or it springs back on every refresh");
    }

    @Test
    @DisplayName("the default theme is the crimson palette the overhaul introduced")
    void defaultTheme() throws Exception {
        String html = html();
        assertTrue(html.contains("APPEARANCE_DEFAULTS") && html.contains("theme: \"crimson\""),
            "a fresh install must open in the crimson theme");
        // The old theme key must still be honoured, so an existing install does not change look.
        assertTrue(html.contains("function currentTheme("),
            "the previous theme key must still be read as a fallback");
    }

    @Test
    @DisplayName("PrismView offers control only after the client says it is allowed")
    void prismViewControlIsGuarded() throws Exception {
        String html = html();
        assertTrue(html.contains("controlEnabled"),
            "the app must read the client's control switch rather than assume it");
        assertTrue(html.contains("remote-control"),
            "the app must be able to turn the client's remote control on");
        // An emergency stop is not optional when a browser can drive a character.
        assertTrue(html.contains("Emergency stop"),
            "PrismView must always offer a way to stop sending input");
    }

    @Test
    @DisplayName("Ctrl+K opens the search tab and focuses a query box that exists")
    void paletteShortcut() throws Exception {
        String html = html();
        assertTrue(html.contains("openPalette"), "Ctrl+K must have a handler");
        assertTrue(html.contains("ctrlKey") && html.contains("toLowerCase() === \"k\""),
            "the handler must be bound to Ctrl+K (the key is lowercased, so match on that)");
        // Focusing an id that the markup never carries would silently do nothing. The input is
        // built by el() with an id property rather than written as literal markup.
        assertTrue(html.contains("id: \"search-query\""),
            "the palette focuses #search-query, so that input must exist");
        assertTrue(html.contains("tab = \"search\""), "Ctrl+K must select the search tab");
    }

    @Test
    @DisplayName("a durability bar comes from the item's real maximum")
    void durabilityUsesTheRealMaximum() throws Exception {
        String html = html();
        assertTrue(html.contains("function durabilityOf("), "durability must be computed once");
        assertTrue(html.contains("maxDamage"),
            "the ratio needs the item's maximum, not only the damage points");
        // An item with no maximum must draw no bar, or a stack of dirt reads as nearly broken.
        Matcher guard = Pattern.compile("if \\(!max \\|\\| max <= 0\\) return null;").matcher(html);
        assertTrue(guard.find(), "a stack that does not wear out must produce no bar");
    }

    @Test
    @DisplayName("an open container is drawn from the handler's slots, not the player's")
    void containerView() throws Exception {
        String html = html();
        assertTrue(html.contains("inventory.openTitle"),
            "an open container must have its own card");
        assertTrue(html.contains("inventory.playerSlotStart"),
            "the player's own slots start partway into the handler, so that offset is needed");
        assertTrue(html.contains("syncId"),
            "a click must carry the handler's sync id so a stale one is refused");
    }

    @Test
    @DisplayName("the world map has a 3D view, and the flat and iso views still exist")
    void mapViews() throws Exception {
        String html = html();
        for (String fn : new String[]{"drawIsoView", "draw3DView"}) {
            assertTrue(html.contains("function " + fn + "("), fn + " must exist");
        }
        assertTrue(html.contains("COLUMN_BUDGET_3D"),
            "the 3D view needs a column budget or a large capture stalls the tab");
    }

    @Test
    @DisplayName("the README names the themes and tabs the app actually ships")
    void readmeMatchesTheApp() throws Exception {
        String html = html();
        // Gradle's test working directory is not guaranteed to be the project root, so walk up
        // from it looking for the README rather than assuming one layout.
        java.nio.file.Path path = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path readmePath = null;
        for (int depth = 0; depth < 4 && path != null; depth++, path = path.getParent()) {
            java.nio.file.Path candidate = path.resolve("README.md");
            if (java.nio.file.Files.exists(candidate)) { readmePath = candidate; break; }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(readmePath != null,
            "no README.md above the working directory, so this check cannot run");
        String readme = java.nio.file.Files.readString(readmePath, StandardCharsets.UTF_8);

        // Every theme label the app defines must be one the README documents, and vice versa, so a
        // new theme cannot ship undocumented and a removed one cannot linger in the docs.
        Matcher labels = Pattern.compile("label: \"([^\"]+)\"").matcher(html);
        int themes = 0;
        while (labels.find()) {
            String label = labels.group(1);
            // Only theme labels are followed by a "vars" block; skip the unrelated ones.
            if (!html.contains("label: \"" + label + "\",\n    vars:")) continue;
            themes++;
            assertTrue(readme.contains("**" + label + "**"),
                "theme '" + label + "' must be documented in the README");
        }
        assertTrue(themes >= 5, "expected the five shipped themes, found " + themes);

        Matcher tabs = Pattern.compile("\\[\"([a-z-]+)\", \"([^\"]+)\", \"[^\"]+\"\\]").matcher(html);
        int tabCount = 0;
        while (tabs.find()) {
            tabCount++;
            String name = tabs.group(2);
            if (name.equals("PrismView")) continue;   // covered by the tab table row
            assertTrue(readme.contains(name) || readme.contains("**" + name + "**"),
                "tab '" + name + "' must be named in the README");
        }
        assertTrue(tabCount >= 15, "expected the full tab list, found " + tabCount);
    }
}
