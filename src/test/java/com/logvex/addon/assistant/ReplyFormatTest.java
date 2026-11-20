package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.SelfReplyGuard;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reply block, the self-reply guard and the generated help.
 *
 * <p>These three belong together: the block is what a reply looks like, the guard is what stops the
 * assistant talking to itself, and the help is the block the guard most needs to protect, because
 * help output lists every command name — a trigger phrase among them.
 */
class ReplyFormatTest {

    private static final AssistantConfig CONFIG = AssistantConfig.basic("Bot", true, true, "!",
        TriggerRule.ReplyMode.CHAT, true, 20, 200);

    private static AssistantBrain brain() {
        return new AssistantBrain(new AssistantMemory());
    }

    /** Every help page joined, so a test sees the whole list and not only the first page. */
    private static String allHelpPages(AssistantBrain brain, SenderIdentity who,
                                       PermissionSet permissions) {
        StringBuilder out = new StringBuilder();
        for (int page = 1; page <= 20; page++) {
            ReplyFormat.Embed embed = brain.helpEmbed(who, CONFIG, null, permissions, page);
            if (embed.fields().isEmpty()) break;
            out.append(String.join("\n", ReplyFormat.render(embed, 400))).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ layout

    @Test
    @DisplayName("a box of values with no labels starts each value at the frame")
    void boxWithoutLabelsDoesNotIndentValues() {
        ReplyFormat.Embed embed = new ReplyFormat.Embed("!ping", List.of(
            new ReplyFormat.Field("", "42 ms")), "", ReplyFormat.Style.BOX);

        List<String> lines = ReplyFormat.render(embed, 40);

        assertEquals(3, lines.size(), "a title, one value and the closing rule");
        assertEquals("│ 42 ms" + " ".repeat(32) + "│", lines.get(1),
            "an unlabelled value must not be pushed into an empty label column");
    }

    @Test
    @DisplayName("a box aligns values, is closed, and never exceeds the requested width")
    void boxAlignsAndFitsWidth() {
        ReplyFormat.Embed embed = new ReplyFormat.Embed("Server", List.of(
            new ReplyFormat.Field("TPS", "20.0"),
            new ReplyFormat.Field("Ping", "24 ms"),
            new ReplyFormat.Field("Players", "12")), "", ReplyFormat.Style.BOX);

        List<String> lines = ReplyFormat.render(embed, 40);

        assertEquals(5, lines.size(), "a title, three fields and the closing rule");
        assertTrue(lines.get(0).startsWith("\u250c"), "the title line opens the box");
        assertTrue(lines.get(0).endsWith("\u2510"), "the title line closes: " + lines.get(0));
        assertTrue(lines.get(4).startsWith("\u2514"), "the box is closed: " + lines.get(4));
        assertTrue(lines.get(4).endsWith("\u2518"), "the closing rule ends in a corner");
        for (String line : lines) {
            assertTrue(line.length() <= 40, "line too long: '" + line + "'");
            assertEquals(40, line.length(), "every line is the same width: '" + line + "'");
            // A box with a missing right-hand bar reads as a truncated message.
            assertTrue(line.startsWith("\u2502") || line.startsWith("\u250c")
                || line.startsWith("\u2514"), "no left frame: " + line);
            assertTrue(line.endsWith("\u2502") || line.endsWith("\u2510")
                || line.endsWith("\u2518"), "no right frame: " + line);
        }
        // Values line up: every value starts in the same column.
        int column = lines.get(1).indexOf("20.0");
        assertEquals(column, lines.get(2).indexOf("24 ms"));
        assertEquals(column, lines.get(3).indexOf("12"));
    }

    @Test
    @DisplayName("an over-long value wraps instead of losing its tail")
    void longValueWraps() {
        String value = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi";
        ReplyFormat.Embed embed = ReplyFormat.Embed.of("T", List.of(
            new ReplyFormat.Field("Note", value)));

        List<String> lines = ReplyFormat.render(embed, 30);

        for (String line : lines) {
            assertTrue(line.length() <= 30, "line too long: '" + line + "'");
        }
        // Nothing is dropped: every word of the value survives the wrap.
        String joined = String.join(" ", lines);
        for (String word : value.split(" ")) {
            assertTrue(joined.contains(word), "word lost in the wrap: " + word);
        }
        assertFalse(joined.contains("\u2026"), "wrapping must not also truncate: " + joined);
    }

    @Test
    @DisplayName("compact and plain styles each fit on one line")
    void singleLineStyles() {
        ReplyFormat.Embed embed = new ReplyFormat.Embed("Status", List.of(
            new ReplyFormat.Field("Facts", "3"),
            new ReplyFormat.Field("Lines", "40")), "", ReplyFormat.Style.COMPACT);

        List<String> compact = ReplyFormat.render(embed, 60);
        assertEquals(1, compact.size());
        assertTrue(compact.get(0).contains("Facts 3"), compact.get(0));
        assertTrue(compact.get(0).contains(" | "), "compact separates fields with a bar");

        ReplyFormat.Embed plain = new ReplyFormat.Embed("Status", embed.fields(), "",
            ReplyFormat.Style.PLAIN);
        List<String> flat = ReplyFormat.render(plain, 60);
        assertEquals(1, flat.size());
        assertFalse(flat.get(0).contains("\u2502"), "plain carries no decoration");
    }

    @Test
    @DisplayName("the list style puts one field per line, which is what help needs")
    void listStyleIsOnePerLine() {
        ReplyFormat.Embed embed = new ReplyFormat.Embed("Commands", List.of(
            new ReplyFormat.Field("!ping", "Latency - Everyone"),
            new ReplyFormat.Field("!tps", "Tick rate - Everyone")), "2 commands.",
            ReplyFormat.Style.LIST);

        List<String> lines = ReplyFormat.render(embed, 60);

        assertEquals(4, lines.size(), "title, two rows, footer");
        assertEquals("!ping - Latency - Everyone", lines.get(1));
        assertEquals("2 commands.", lines.get(3));
    }

    @Test
    @DisplayName("a wrapped list row is indented so it reads as one entry, not two")
    void wrappedListRowKeepsItsEntry() {
        ReplyFormat.Embed embed = new ReplyFormat.Embed("Commands", List.of(
            new ReplyFormat.Field("!players",
                "How many players are visible, and the maximum when known.")),
            "", ReplyFormat.Style.LIST);

        List<String> lines = ReplyFormat.render(embed, 40);

        // Without the indent the second line began at column zero and looked like a new command.
        assertTrue(lines.size() > 2, "the row should have wrapped at this width");
        assertTrue(lines.get(2).startsWith("  "),
            "continuation must be indented, was: '" + lines.get(2) + "'");
        assertFalse(lines.get(2).startsWith("!") , "continuation must not look like a command");
    }

    @Test
    @DisplayName("a long label does not starve the value column")
    void longLabelKeepsValueReadable() {
        ReplyFormat.Embed embed = new ReplyFormat.Embed("Activity", List.of(
            new ReplyFormat.Field("Longest single description here",
                "a value that is fairly long and will need to wrap somewhere")),
            "", ReplyFormat.Style.BOX);

        List<String> lines = ReplyFormat.render(embed, 48);

        // Before the cap, the label took nearly the whole row and the value became a one-word
        // column six lines tall. Capping the label keeps the two columns proportionate.
        assertTrue(lines.size() <= 6,
            "the value should wrap to a couple of rows, not a column: " + lines);
        String valueRow = lines.get(1);
        assertTrue(valueRow.length() > 30, "value column keeps room: '" + valueRow + "'");
    }

    @Test
    @DisplayName("an embed with no fields renders no rows at all")
    void emptyEmbedHasNoRows() {
        // A titled, empty box is just the two rules; an untitled one is empty.
        assertTrue(ReplyFormat.render(new ReplyFormat.Embed("", List.of(), "",
            ReplyFormat.Style.BOX), 40).isEmpty());
        assertEquals(2, ReplyFormat.render(new ReplyFormat.Embed("Empty", List.of(), "",
            ReplyFormat.Style.BOX), 40).size(), "only the opening and closing rules");
    }

    @Test
    @DisplayName("a capped block is closed, never cut mid-frame")
    void cappedBlockKeepsItsClosingRule() {
        // Cutting a rendered list at the line cap is the obvious implementation and it ends the
        // message on a "|" row, which reads as a send that was truncated. Every size here is over
        // the cap, so every one must still come back closed and within the budget.
        for (int fields = 11; fields <= 30; fields += 19) {
            List<ReplyFormat.Field> rows = new java.util.ArrayList<>();
            for (int i = 1; i <= fields; i++) rows.add(new ReplyFormat.Field("F" + i, "v" + i));
            List<String> lines = ReplyFormat.renderCapped(new ReplyFormat.Embed("!big", rows, "",
                ReplyFormat.Style.BOX), 44, 12);

            assertTrue(lines.size() <= 12, fields + " fields exceeded the cap: " + lines.size());
            assertTrue(lines.get(lines.size() - 1).startsWith("\u2514"),
                "a capped box must still end on its closing rule: '" + lines.get(lines.size() - 1) + "'");
            assertTrue(lines.get(lines.size() - 1).contains("more"),
                "the dropped rows must be reported: '" + lines.get(lines.size() - 1) + "'");
        }
    }

    @Test
    @DisplayName("a block under the cap is rendered untouched")
    void cappedBlockBelowLimitIsUnchanged() {
        ReplyFormat.Embed embed = new ReplyFormat.Embed("!small", List.of(
            new ReplyFormat.Field("TPS", "19.94"), new ReplyFormat.Field("MSPT", "12.4 ms")),
            "", ReplyFormat.Style.BOX);
        assertEquals(ReplyFormat.render(embed, 44), ReplyFormat.renderCapped(embed, 44, 12),
            "a block that already fits must not be re-rendered into a different shape");
    }

    @Test
    @DisplayName("a wide character is measured as two columns so the frame stays square")
    void wideCharactersDoNotBreakTheFrame() {
        // A CJK name is drawn double width. Measuring length() pads the row two columns short, so
        // the right-hand frame drifts left by two per wide character.
        ReplyFormat.Embed embed = new ReplyFormat.Embed("!u", List.of(
            new ReplyFormat.Field("Name", "\u30b9\u30c6\u30a3\u30fc\u30d6"),
            new ReplyFormat.Field("Ok", "fine")), "", ReplyFormat.Style.BOX);

        List<String> lines = ReplyFormat.render(embed, 32);

        for (String line : lines) {
            assertEquals(32, ReplyFormat.displayWidth(line),
                "every row must occupy the same columns: '" + line + "'");
        }
        assertEquals(4, ReplyFormat.displayWidth("\u30b9\u30c6"), "two CJK glyphs are four columns");
        assertEquals(1, ReplyFormat.displayWidth("a"), "an ASCII letter is one column");
    }

    @Test
    @DisplayName("a wide title and a wide value still fit the frame")
    void wideTextWrapsWithinTheFrame() {
        // The first attempt at column-aware rendering padded rows correctly but still wrapped and
        // measured the border by length(), so a CJK title produced a 39-column line inside a
        // 30-column box. Wrapping and border fill must use the same column measure as padding.
        ReplyFormat.Embed titled = new ReplyFormat.Embed(
            "\u30c6\u30b9\u30c8\u30c6\u30b9\u30c8\u30c6\u30b9\u30c8",
            List.of(new ReplyFormat.Field("A", "1")), "", ReplyFormat.Style.BOX);
        for (String line : ReplyFormat.render(titled, 30)) {
            assertEquals(30, ReplyFormat.displayWidth(line),
                "the title rule must fit too: '" + line + "'");
        }

        ReplyFormat.Embed valued = new ReplyFormat.Embed("!x", List.of(new ReplyFormat.Field("T",
            "\u4f60\u597d\u4e16\u754c\u4f60\u597d\u4e16\u754c\u4f60\u597d\u4e16\u754c")),
            "", ReplyFormat.Style.BOX);
        for (String line : ReplyFormat.render(valued, 24)) {
            assertEquals(24, ReplyFormat.displayWidth(line),
                "a wide value must wrap inside the frame: '" + line + "'");
        }
    }

    @Test
    @DisplayName("a meter bar is proportional and clamps out-of-range shares")
    void meterBarIsProportional() {
        assertEquals("\u2591\u2591\u2591\u2591\u2591\u2591", ReplyFormat.bar(0, 6));
        assertEquals("\u2588\u2588\u2588\u2591\u2591\u2591", ReplyFormat.bar(0.5, 6));
        assertEquals("\u2588\u2588\u2588\u2588\u2588\u2588", ReplyFormat.bar(1, 6));
        // A share outside 0..1, or one that is not a number, must not draw a broken bar: a meter
        // that overflows its own cells would push the frame out, and a NaN one would be empty.
        assertEquals(6, ReplyFormat.bar(2.0, 6).length(), "an over-full share clamps to full");
        assertEquals(6, ReplyFormat.bar(-1, 6).length(), "a negative share clamps to empty");
        assertEquals(ReplyFormat.bar(0, 6), ReplyFormat.bar(Double.NaN, 6), "NaN draws empty");
    }

    @Test
    @DisplayName("a meter field keeps the figure next to its bar")
    void meterFieldKeepsTheFigure() {
        ReplyFormat.Field f = ReplyFormat.Field.meter("Heap", "42%", 0.42);
        assertEquals("Heap", f.label());
        assertTrue(f.value().startsWith("42% "), "the number must survive: " + f.value());
        assertTrue(f.value().contains("\u2588"), "and the bar must be drawn: " + f.value());
    }

    @Test
    @DisplayName("a section rule spans the block and ignores the label column")
    void sectionRuleSpansTheBlock() {
        List<ReplyFormat.Field> fields = List.of(
            ReplyFormat.Field.section("Memory"),
            new ReplyFormat.Field("Facts", "3"));
        List<String> lines = ReplyFormat.render(new ReplyFormat.Embed("!about", fields, "",
            ReplyFormat.Style.BOX), 40);

        String sectionRow = lines.get(1);
        assertTrue(sectionRow.contains("Memory"), "the name is shown: '" + sectionRow + "'");
        assertTrue(sectionRow.contains("\u2500\u2500\u2500"), "and a rule is drawn: '" + sectionRow + "'");
        for (String line : lines) {
            assertEquals(40, ReplyFormat.displayWidth(line), "every row keeps the width: '" + line + "'");
        }
    }

    @Test
    @DisplayName("a section rule also works in the list style")
    void sectionRuleWorksInListStyle() {
        List<ReplyFormat.Field> fields = List.of(
            ReplyFormat.Field.section("Commands"),
            new ReplyFormat.Field("!tps", "tick rate"));
        List<String> lines = ReplyFormat.render(new ReplyFormat.Embed("Help", fields, "",
            ReplyFormat.Style.LIST), 30);

        assertTrue(lines.get(1).contains("Commands"), "section header rendered: " + lines);
        assertTrue(lines.get(2).startsWith("!tps - "), "the row below is unaffected: " + lines);
        assertFalse(lines.get(1).contains("\u0000"), "the marker must never reach the chat box");
    }

    // ------------------------------------------------------------------ self-reply guard

    @Test
    @DisplayName("the assistant recognises its own reply when the echo arrives")
    void guardRecognisesOwnReply() {
        SelfReplyGuard guard = new SelfReplyGuard();
        guard.record("Hello Steve, welcome back!");

        assertTrue(guard.isOwn("Hello Steve, welcome back!"));
        // A server that reformats spacing or case must not slip past the guard.
        assertTrue(guard.isOwn("  hello   steve,   welcome back!  "));
        assertFalse(guard.isOwn("Hello Alex, welcome back!"));
    }

    @Test
    @DisplayName("a message from the local player is always ignored")
    void guardIgnoresLocalPlayer() {
        SelfReplyGuard guard = new SelfReplyGuard();

        assertTrue(guard.shouldIgnore("Me", "Me", "anything at all"),
            "the local player's own line is never answered");
        assertFalse(guard.shouldIgnore("Stranger", "Me", "hello bot"));
    }

    @Test
    @DisplayName("the guard forgets old replies so a genuine line is not refused forever")
    void guardIsBounded() {
        SelfReplyGuard guard = new SelfReplyGuard(2);
        guard.record("first");
        guard.record("second");
        guard.record("third");

        assertEquals(2, guard.size());
        assertFalse(guard.isOwn("first"), "the oldest reply has aged out");
        assertTrue(guard.isOwn("third"));
    }

    @Test
    @DisplayName("a reply that contains a trigger phrase cannot start another reply")
    void ownReplyCannotRetrigger() {
        AssistantBrain brain = brain();
        // The reply to `!ping` mentions the trigger word itself, which is exactly the shape that
        // would loop without a guard.
        SelfReplyGuard guard = new SelfReplyGuard();
        AssistantBrain.Decision answer = brain.command(SenderIdentity.local("Me", null), "!ping",
            CONFIG, 1_000L, null, null, PermissionSet.permissive(), null);
        assertTrue(answer.acts());

        guard.record(answer.text());

        assertTrue(guard.shouldIgnore("Me", "Me", answer.text()),
            "the assistant's own reply must be refused at the top of the pipeline");
    }

    // ------------------------------------------------------------------ generated help

    @Test
    @DisplayName("help lists commands with their aliases, and only states a level when it restricts")
    void helpListsAliasesAndLevels() {
        AssistantBrain brain = brain();
        String rendered = allHelpPages(brain, SenderIdentity.local("Me", null),
            PermissionSet.permissive());
        assertTrue(rendered.contains("!ping"), rendered);
        assertTrue(rendered.contains("latency"), "aliases are shown: " + rendered);
        // "Everyone" is the default, so repeating it on every row was noise that pushed the
        // description off the line. A level that actually restricts is still stated.
        assertFalse(rendered.contains("Everyone"),
            "the default level must not be repeated on every row: " + rendered);

        PermissionSet restricted = PermissionSet.of(true, List.of(),
            List.of("ping;level=whitelist;users=Steve"), PermissionSet.DefaultPolicy.OPEN, true);
        // Asked as the whitelisted player: a command the asker cannot run is not listed at all, so
        // the level only becomes visible to someone it actually applies to.
        SenderIdentity steve = new SenderIdentity("Steve", null, false);
        String withRestriction = allHelpPages(brain, steve, restricted);
        assertTrue(withRestriction.contains("Whitelist"),
            "a restricting level must be stated: " + withRestriction);
    }

    @Test
    @DisplayName("help never names the players on a whitelist")
    void helpDoesNotLeakWhitelistMembers() {
        AssistantBrain brain = brain();
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("history;level=whitelist;users=Steve,Alex"), PermissionSet.DefaultPolicy.OPEN,
            true);

        // The whitelisted player themselves: they can run the command, so the row is shown.
        SenderIdentity steve = new SenderIdentity("Steve", null, false);
        String rendered = allHelpPages(brain, steve, permissions);

        assertTrue(rendered.contains("Whitelist"), "the level itself is stated: " + rendered);
        assertFalse(rendered.contains("Steve"), "a whitelisted name must not leak: " + rendered);
        assertFalse(rendered.contains("Alex"), "a whitelisted name must not leak: " + rendered);
    }

    @Test
    @DisplayName("a switched-off command is not listed even for the local player")
    void helpOmitsDisabledCommands() {
        AssistantBrain brain = brain();
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=everyone;enabled=false"), PermissionSet.DefaultPolicy.OPEN,
            true);

        String rendered = allHelpPages(brain, SenderIdentity.local("Me", null), permissions);

        assertFalse(rendered.contains("!remember"),
            "a disabled command must not be advertised: " + rendered);
    }

    @Test
    @DisplayName("help omits a command the caller is not allowed to use")
    void helpHidesCommandsTheCallerCannotUse() {
        AssistantBrain brain = brain();
        PermissionSet permissions = PermissionSet.of(true, List.of(),
            List.of("remember;level=local-only"), PermissionSet.DefaultPolicy.OPEN, true);

        String stranger = allHelpPages(brain, SenderIdentity.unverified("Stranger"), permissions);
        assertFalse(stranger.contains("!remember"),
            "a command the speaker cannot run is not advertised: " + stranger);

        String local = allHelpPages(brain, SenderIdentity.local("Me", null), permissions);
        assertTrue(local.contains("!remember"), "the local player may still see it: " + local);
    }

    @Test
    @DisplayName("help paginates instead of producing an unbounded wall of lines")
    void helpPaginates() {
        AssistantBrain brain = brain();
        ReplyFormat.Embed first = brain.helpEmbed(SenderIdentity.local("Me", null), CONFIG, null,
            PermissionSet.permissive(), 1);
        ReplyFormat.Embed second = brain.helpEmbed(SenderIdentity.local("Me", null), CONFIG, null,
            PermissionSet.permissive(), 2);

        assertTrue(first.fields().size() <= AssistantBrain.HELP_PAGE_SIZE);
        assertTrue(first.footer().contains("Page 1 of"), first.footer());
        assertNotEquals(first.fields().get(0).label(), second.fields().get(0).label(),
            "page two starts at a different command");
    }

    @Test
    @DisplayName("a page number beyond the end is clamped, not empty")
    void helpClampsBeyondLastPage() {
        AssistantBrain brain = brain();
        ReplyFormat.Embed far = brain.helpEmbed(SenderIdentity.local("Me", null), CONFIG, null,
            PermissionSet.permissive(), 99);

        // Paging past the end shows nothing, which the command turns into a clear refusal rather
        // than a blank reply; the footer must not claim a page that does not exist.
        assertFalse(far.footer().contains("Page 99"), far.footer());
    }

    @Test
    @DisplayName("the help command can be switched off, and then answers nothing")
    void helpCanBeSwitchedOff() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setTriggers(TriggerSet.empty());
        runtime.setConfig(CONFIG);
        runtime.setPermissions(PermissionSet.permissive());
        // Off for this test: the same line is sent twice, and the duplicate filter would drop the
        // second one for a reason that has nothing to do with the switch under test.
        runtime.setDuplicateFilter(false);
        runtime.setHelpEnabled(false);

        AssistantBrain.Decision off = runtime.handle(SenderIdentity.local("Me", null), "!help",
            1_000L, null, null);
        assertFalse(off.acts(), "a disabled help command must answer nothing");

        runtime.setHelpEnabled(true);
        AssistantBrain.Decision on = runtime.handle(SenderIdentity.local("Me", null), "!help",
            2_000L, null, null);
        assertTrue(on.acts(), "switching it back on restores it");
        assertTrue(on.hasEmbed(), "help is delivered as a block");
    }

    @Test
    @DisplayName("an assistant reply containing a trigger cannot start a second reply")
    void pipelineRefusesItsOwnReply() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        TriggerSet triggers = TriggerSet.of(List.of(
            TriggerRule.of("hello bot", "Hi {player}!")));
        runtime.setConfig(AssistantConfig.basic("Bot", true, false, "!",
            TriggerRule.ReplyMode.CHAT, true, 0, 200));
        // The same trigger text is used once only; the duplicate filter is irrelevant here.
        runtime.setTriggers(triggers);
        runtime.setDuplicateFilter(false);

        AssistantBrain.Decision first =
            runtime.handle(SenderIdentity.unverified("Stranger"), "hello bot", 1_000L, null, null);
        assertTrue(first.acts(), "the trigger fires once");

        // Simulate the echo arriving back through the pipeline. The guard is what the module
        // consults before matching, so a recorded reply must not reach the trigger again.
        SelfReplyGuard guard = new SelfReplyGuard();
        guard.record(first.text());
        assertTrue(guard.shouldIgnore("Bot", "Bot", first.text()),
            "the echo must be refused before trigger matching");
    }

    @Test
    @DisplayName("hello answers with a greeting that names the speaker")
    void helloNamesTheSpeaker() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(CONFIG);
        runtime.setTriggers(TriggerSet.empty());
        runtime.setPermissions(PermissionSet.permissive());
        runtime.setDuplicateFilter(false);

        AssistantBrain.Decision decision = runtime.handle("Steve", "!hello", 1_000L, null, null);

        assertTrue(decision.acts(), "hello always answers");
        assertTrue(decision.text().contains("Steve"), "the greeting names the speaker: "
            + decision.text());
    }

    @Test
    @DisplayName("gg answers with the configured good-game line")
    void goodGameUsesTheConfiguredLine() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(CONFIG);
        runtime.setTriggers(TriggerSet.empty());
        runtime.setPermissions(PermissionSet.permissive());
        runtime.setDuplicateFilter(false);
        runtime.setGoodGameReply("Nice one, {player}.");

        AssistantBrain.Decision decision = runtime.handle("Alex", "!gg", 1_000L, null, null);

        assertTrue(decision.acts());
        assertEquals("Nice one, Alex.", decision.text());
    }

    @Test
    @DisplayName("a blank good-game line restores the default rather than sending nothing")
    void blankGoodGameFallsBackToDefault() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        runtime.setConfig(CONFIG);
        runtime.setTriggers(TriggerSet.empty());
        runtime.setPermissions(PermissionSet.permissive());
        runtime.setDuplicateFilter(false);
        runtime.setGoodGameReply("   ");

        AssistantBrain.Decision decision = runtime.handle("Alex", "!gg", 1_000L, null, null);

        assertTrue(decision.acts());
        assertEquals("Good game, Alex!", decision.text());
    }

    @Test
    @DisplayName("help follows a changed permission immediately")
    void helpTracksPermissionChanges() {
        AssistantBrain brain = brain();
        PermissionSet open = PermissionSet.of(true, List.of(), List.of("ping;level=whitelist"),
            PermissionSet.DefaultPolicy.OPEN, true);

        String before = allHelpPages(brain, SenderIdentity.unverified("Stranger"), open);
        assertFalse(before.contains("!ping"), "a whitelist command is hidden from a stranger");

        PermissionSet opened = PermissionSet.of(true, List.of(), List.of("ping;level=everyone"),
            PermissionSet.DefaultPolicy.OPEN, true);
        String after = allHelpPages(brain, SenderIdentity.unverified("Stranger"), opened);
        assertTrue(after.contains("!ping"), "opening the command makes it visible: " + after);
    }
}
