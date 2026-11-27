package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionJson;
import com.logvex.addon.companion.core.CompanionRouter;
import com.logvex.addon.companion.core.SlotAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inventory click protocol, exercised through the router.
 *
 * <p>These tests exist because a slot click is the one action in the whole API that changes the
 * player's possessions. Every rejection path is pinned here: an unknown action, a missing slot, a
 * fractional slot, an out-of-range button and a malformed body must all be refused before the game
 * thread is touched, so a bad request cannot move an item.
 */
class CompanionInventoryTest {
    private static final String TOKEN = "test-token-abcdefghijklmnop";

    private CompanionConfig config() {
        CompanionConfig config = new CompanionConfig();
        config.setToken(TOKEN);
        return config;
    }

    private CompanionRouter router(FakeCompanionApi api) {
        return new CompanionRouter(config(), api, CompanionClientThread.direct(),
            new CompanionEventBus(50), "<html>app</html>");
    }

    private static CompanionRouter.Request get(String path) {
        return new CompanionRouter.Request("GET", path, Map.of(), "Bearer " + TOKEN, null, "127.0.0.1");
    }

    private static CompanionRouter.Request post(String path, String body) {
        return new CompanionRouter.Request("POST", path, Map.of(), "Bearer " + TOKEN, body, "127.0.0.1");
    }

    @Test
    @DisplayName("a well-formed click reaches the game with every field intact")
    void clickIsForwarded() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"pickup\",\"slot\":12,\"button\":1,\"syncId\":7}"));

        assertEquals(200, response.status());
        assertEquals(1, api.clicks.size());
        assertEquals(12, api.lastClickSlot);
        assertEquals(1, api.lastClickButton);
        assertEquals("pickup", api.lastClickAction);
        assertEquals(7, api.lastClickSyncId, "the sync id must survive, or a stale click could land");
    }

    @Test
    @DisplayName("every action name the app can send is accepted")
    void allActionsAreAccepted() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        for (String action : SlotAction.wireNames()) {
            CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click",
                "{\"action\":\"" + action + "\",\"slot\":0}"));
            assertEquals(200, response.status(), action + " should be accepted");
        }
        assertEquals(SlotAction.wireNames().size(), api.clicks.size());
    }

    @Test
    @DisplayName("an unknown action is refused and never reaches the game")
    void unknownActionIsRefused() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"teleport_item\",\"slot\":0}"));

        assertEquals(400, response.status());
        assertTrue(api.clicks.isEmpty(), "a refused action must not be forwarded");
        assertTrue(response.body().contains("action"), "the message should name what is missing");
    }

    @Test
    @DisplayName("a click with no slot is refused")
    void missingSlotIsRefused() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"pickup\"}"));

        assertEquals(400, response.status());
        assertTrue(api.clicks.isEmpty());
    }

    @Test
    @DisplayName("a fractional slot is refused rather than truncated onto a neighbour")
    void fractionalSlotIsRefused() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"pickup\",\"slot\":12.5}"));

        assertEquals(400, response.status());
        assertTrue(api.clicks.isEmpty(), "12.5 must not become slot 12");
    }

    @Test
    @DisplayName("a slot written as 12.0 is still slot 12, because a browser may send it that way")
    void integralDoubleIsAccepted() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"pickup\",\"slot\":12.0}"));

        assertEquals(200, response.status());
        assertEquals(12, api.lastClickSlot);
    }

    @Test
    @DisplayName("a button outside 0-8 is refused")
    void outOfRangeButtonIsRefused() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        assertEquals(400, router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"swap\",\"slot\":0,\"button\":9}")).status());
        assertEquals(400, router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"swap\",\"slot\":0,\"button\":-1}")).status());
        assertTrue(api.clicks.isEmpty());
    }

    @Test
    @DisplayName("a malformed body is refused, not answered with a 500")
    void malformedBodyIsRefused() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click", "not json"));

        assertEquals(400, response.status());
        assertTrue(api.clicks.isEmpty());
    }

    @Test
    @DisplayName("the outside slot is allowed, because that is how a drop is expressed")
    void outsideSlotIsAllowed() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("/api/v1/inventory/click",
            "{\"action\":\"throw\",\"slot\":-999,\"button\":1}"));

        assertEquals(200, response.status());
        assertEquals(-999, api.lastClickSlot);
    }

    @Test
    @DisplayName("the action list is served so the app can build its controls without hard-coding")
    void actionListIsServed() {
        CompanionRouter router = router(new FakeCompanionApi());

        CompanionRouter.Response response = router.handle(get("/api/v1/inventory/actions"));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("quick_move"), "shift-click must be listed");
        assertTrue(response.body().contains("pickup"));
    }

    @Test
    @DisplayName("the inventory route serves the snapshot's inventory, not a second copy")
    void inventoryRouteServesSnapshot() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(get("/api/v1/inventory"));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("\"available\""));
        assertTrue(api.calls.contains("snapshot"), "the route must read the live snapshot");
    }

    @Test
    @DisplayName("an unauthenticated click is refused before it is even parsed")
    void clickRequiresAToken() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(new CompanionRouter.Request(
            "POST", "/api/v1/inventory/click", Map.of(), null,
            "{\"action\":\"pickup\",\"slot\":0}", "127.0.0.1"));

        assertEquals(401, response.status());
        assertTrue(api.clicks.isEmpty());
    }

    @Test
    @DisplayName("the wire names round-trip, including a hyphen and a capital")
    void wireNamesRoundTrip() {
        assertEquals(SlotAction.QUICK_MOVE, SlotAction.fromWire("quick_move"));
        assertEquals(SlotAction.QUICK_MOVE, SlotAction.fromWire("quick-move"));
        assertEquals(SlotAction.PICKUP, SlotAction.fromWire("  PICKUP  "));
        assertNull(SlotAction.fromWire("nonsense"));
        assertNull(SlotAction.fromWire(null));
    }

    @Test
    @DisplayName("the integer helper refuses a non-number rather than answering zero")
    void integerHelperIsStrict() {
        com.google.gson.JsonObject body = CompanionJson.parseObject("{\"a\":\"x\",\"b\":3.5,\"c\":7}");
        assertNotNull(body);
        assertNull(CompanionJson.integer(body, "a", null), "a string is not an integer");
        assertNull(CompanionJson.integer(body, "b", null), "3.5 is not an integer");
        assertEquals(7, CompanionJson.integer(body, "c", null));
        assertEquals(4, CompanionJson.integer(body, "missing", 4), "the fallback is used when absent");
    }

    @Test
    @DisplayName("an empty inventory view still reports itself, so the app can say why it is empty")
    void unavailableInventorySerialises() {
        String json = CompanionJson.write(
            com.logvex.addon.companion.core.CompanionSnapshot.InventoryView.unavailable());
        assertTrue(json.contains("\"available\":false"));
        assertFalse(json.contains("syncId\":7"));
    }
}
