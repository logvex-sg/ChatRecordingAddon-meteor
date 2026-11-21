package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.ReplyDelivery;
import com.logvex.addon.assistant.core.SenderIdentity;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A card asked for by the local player must cost one chat message, not one per row.
 *
 * <p>{@code !cmds1} rendered to six rows and each row was sent as its own {@code /msg}, so asking
 * for one help page put six commands on the wire. The pacer spread them out but did not reduce the
 * count, which is what a flood filter counts. The regression is that the number of messages scales
 * with the number of rows; these tests pin it to one.
 */
class ReplyDeliveryTest {

    private static final int CAP = 12;
    private static final int WIDTH = 52;

    private static AssistantRuntime runtime() {
        AssistantRuntime runtime = new AssistantRuntime(new AssistantMemory());
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured());
        runtime.setTriggers(set);
        runtime.brain().setTriggerSet(set);
        runtime.setConfig(AssistantConfig.DEFAULTS);
        runtime.setDuplicateFilter(false);
        return runtime;
    }

    private static ReplyDelivery.Plan planFor(String command, boolean localSpeaker) {
        AssistantBrain.Decision decision = runtime().handle(
            SenderIdentity.local("Me", null), command, 1000L, ServerStats.OFFLINE, null);
        return ReplyDelivery.plan(decision.delivery(),
            decision.linesCapped(WIDTH, null, CAP),
            com.logvex.addon.assistant.core.ReplyFormat.oneLine(decision.embed(), WIDTH),
            localSpeaker);
    }

    @Test
    @DisplayName("a local player's card is one message, however many rows it has")
    void localCardIsOneMessage() {
        for (String command : List.of("!cmds", "!cmds1", "!cmds2", "!tps", "!status")) {
            ReplyDelivery.Plan plan = planFor(command, true);
            assertEquals(ReplyDelivery.Mode.LOCAL_ONE_MESSAGE, plan.mode(),
                command + " is whispered to the asker, so it should be printed locally");
            assertEquals(1, plan.lines().size() >= 1 ? 1 : 0,
                command + " must produce at least one row");
            assertTrue(!plan.joined().isBlank(), command + " must have a body: " + plan.joined());
        }
    }

    @Test
    @DisplayName("a card is one message but keeps every row")
    void localCardKeepsEveryRow() {
        ReplyDelivery.Plan plan = planFor("!cmds1", true);
        // The rows must still be there: collapsing to one message must not drop content.
        assertTrue(plan.lines().size() >= 5,
            "the page still renders its rows, got " + plan.lines().size());
        assertEquals(plan.lines().size(), plan.joined().split("\n", -1).length,
            "every row survives into the single message");
    }

    @Test
    @DisplayName("a remote asker still gets one message per row, paced")
    void remoteCardIsPacedLines() {
        ReplyDelivery.Plan plan = planFor("!cmds1", false);
        assertEquals(ReplyDelivery.Mode.SERVER_PACED_LINES, plan.mode(),
            "a remote asker can only be reached through the server");
        assertTrue(plan.lines().size() > 1, "a page is more than one row");
    }

    @Test
    @DisplayName("a one-line reply to a remote asker is a single message")
    void remoteSingleLine() {
        ReplyDelivery.Plan plan = ReplyDelivery.plan(AssistantBrain.Delivery.PRIVATE,
            List.of("just one row"), "just one row", false);
        assertEquals(ReplyDelivery.Mode.SERVER_ONE_LINE, plan.mode());
    }

    @Test
    @DisplayName("public chat collapses a block to one line instead of flooding")
    void publicChatCollapses() {
        ReplyDelivery.Plan plan = ReplyDelivery.plan(AssistantBrain.Delivery.CHAT,
            List.of("a", "b", "c"), "a | b | c", false);
        assertEquals(ReplyDelivery.Mode.SERVER_ONE_LINE, plan.mode());
        assertEquals(1, plan.lines().size(), "public chat cannot carry a block");
        assertEquals("a | b | c", plan.lines().get(0));
    }

    @Test
    @DisplayName("nothing to say is not a message")
    void emptyIsNone() {
        assertEquals(ReplyDelivery.Mode.NONE, ReplyDelivery.plan(
            AssistantBrain.Delivery.PRIVATE, List.of(), "", true).mode());
        assertEquals(ReplyDelivery.Mode.NONE, ReplyDelivery.plan(
            AssistantBrain.Delivery.NOTIFY, List.of("x"), "x", true).mode(),
            "a notification is not a chat message and is not planned here");
        assertEquals(ReplyDelivery.Mode.NONE, ReplyDelivery.plan(
            AssistantBrain.Delivery.SILENT, List.of("x"), "x", true).mode());
    }

    @Test
    @DisplayName("a local card sends nothing to the server")
    void localCardSendsNothing() {
        // The whole point: the mode must not be one that writes to the network.
        for (String command : List.of("!cmds", "!cmds1", "!tps", "!help")) {
            ReplyDelivery.Plan plan = planFor(command, true);
            assertTrue(plan.mode() == ReplyDelivery.Mode.LOCAL_ONE_MESSAGE
                    || plan.mode() == ReplyDelivery.Mode.NONE,
                command + " must not send a server message for a local asker, got " + plan.mode());
        }
    }

    @Test
    @DisplayName("the single message still holds every row, newline separated")
    void joinedKeepsEveryRow() {
        // ChatHud breaks on \n, so the block keeps its shape inside one message. If the rows were
        // joined some other way the table would arrive as one unreadable line.
        ReplyDelivery.Plan plan = ReplyDelivery.plan(AssistantBrain.Delivery.PRIVATE,
            List.of("row one", "row two", "row three"), "row one", true);
        assertEquals(ReplyDelivery.Mode.LOCAL_ONE_MESSAGE, plan.mode());
        assertEquals(3, plan.joined().split("\n", -1).length);
        assertTrue(plan.joined().startsWith("row one"));
        assertTrue(plan.joined().endsWith("row three"));
    }

    @Test
    @DisplayName("blank rows are dropped so the message has no empty lines")
    void blankRowsDropped() {
        ReplyDelivery.Plan plan = ReplyDelivery.plan(AssistantBrain.Delivery.PRIVATE,
            List.of("a", "", "  ", "b"), "a", true);
        assertEquals(2, plan.lines().size());
    }
}
