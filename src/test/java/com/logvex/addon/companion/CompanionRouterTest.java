package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionApi;
import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionRouter;
import com.logvex.addon.companion.core.CompanionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The companion API surface: routing, authentication, rate limiting, error boundaries and the action
 * endpoints, all exercised on a plain method call with no socket.
 */
class CompanionRouterTest {
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

    private static CompanionRouter.Request unauthenticated(String method, String path) {
        return new CompanionRouter.Request(method, path, Map.of(), null, null, "127.0.0.1");
    }

    @Test
    @DisplayName("the app shell and health probe are public, the API is not")
    void shellIsPublicApiIsNot() {
        CompanionRouter router = router(new FakeCompanionApi());

        assertEquals(200, router.handle(unauthenticated("GET", "")).status(), "the app shell must load");
        assertEquals(200, router.handle(unauthenticated("GET", "index.html")).status());
        assertEquals(200, router.handle(unauthenticated("GET", "health")).status());
        assertEquals(401, router.handle(unauthenticated("GET", "api/v1/snapshot")).status());
        assertEquals(401, router.handle(unauthenticated("POST", "api/v1/bot/start")).status());
    }

    @Test
    @DisplayName("a wrong or missing token is refused with a 401 and no data")
    void wrongTokenRefused() {
        CompanionRouter router = router(new FakeCompanionApi());

        CompanionRouter.Request wrong = new CompanionRouter.Request(
            "GET", "api/v1/snapshot", Map.of(), "Bearer not-the-token", null, "127.0.0.1");
        CompanionRouter.Response response = router.handle(wrong);

        assertEquals(401, response.status());
        assertTrue(response.body().contains("Not authorised"));
        assertFalse(response.body().contains("modules"), "no data may leak on a failed auth");
    }

    @Test
    @DisplayName("a token in the query string is accepted, for the browser and EventSource")
    void queryTokenAccepted() {
        CompanionRouter router = router(new FakeCompanionApi());
        CompanionRouter.Request request = new CompanionRouter.Request(
            "GET", "api/v1/snapshot", Map.of("token", TOKEN), null, null, "127.0.0.1");

        assertEquals(200, router.handle(request).status());
    }

    @Test
    @DisplayName("the snapshot endpoint returns the client's picture")
    void snapshotServed() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(get("api/v1/snapshot"));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("node-1"));
        assertEquals(1, api.calls.stream().filter("snapshot"::equals).count());
    }

    @Test
    @DisplayName("every bot control endpoint calls the matching action")
    void botControls() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        assertEquals(200, router.handle(post("api/v1/bot/start", "")).status());
        assertEquals(200, router.handle(post("api/v1/bot/pause", "")).status());
        assertEquals(200, router.handle(post("api/v1/bot/resume", "")).status());
        assertEquals(200, router.handle(post("api/v1/bot/stop", "")).status());
        assertEquals(200, router.handle(post("api/v1/bot/emergency-stop", "")).status());

        assertTrue(api.calls.contains("startBot"));
        assertTrue(api.calls.contains("pauseBot"));
        assertTrue(api.calls.contains("resumeBot"));
        assertTrue(api.calls.contains("stopBot"));
        assertTrue(api.calls.contains("emergencyStopBot"));
    }

    @Test
    @DisplayName("queueing a task passes the objective through, and an empty one is rejected")
    void enqueueTask() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response ok = router.handle(post("api/v1/tasks", "{\"objective\":\"mine oak_log 64\"}"));
        assertEquals(200, ok.status());
        assertEquals("mine oak_log 64", api.lastObjective);

        CompanionRouter.Response empty = router.handle(post("api/v1/tasks", "{\"objective\":\"  \"}"));
        assertEquals(400, empty.status());
        assertTrue(empty.body().contains("No objective"));
    }

    @Test
    @DisplayName("a malformed body is rejected without throwing")
    void malformedBody() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        assertEquals(400, router.handle(post("api/v1/tasks", "not json at all")).status());
        assertEquals(400, router.handle(post("api/v1/chat", "{")).status());
        assertEquals(0, api.calls.stream().filter(call -> call.startsWith("enqueueTask")).count());
    }

    @Test
    @DisplayName("a module toggle carries the name from the path and the value from the body")
    void moduleToggle() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Response response = router.handle(post("api/v1/modules/chat-logger", "{\"active\":true}"));

        assertEquals(200, response.status());
        assertEquals("chat-logger", api.lastModule);
        assertEquals(Boolean.TRUE, api.lastModuleActive);
    }

    @Test
    @DisplayName("a task can be cancelled by id")
    void cancelTask() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Request request = new CompanionRouter.Request(
            "DELETE", "api/v1/tasks/abc-123", Map.of(), "Bearer " + TOKEN, null, "127.0.0.1");
        CompanionRouter.Response response = router.handle(request);

        assertEquals(200, response.status());
        assertEquals("abc-123", api.lastCancelledTask);
    }

    @Test
    @DisplayName("chat and command endpoints reject an empty payload and accept a real one")
    void chatAndCommand() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        assertEquals(400, router.handle(post("api/v1/chat", "{\"message\":\"\"}")).status());
        assertEquals(200, router.handle(post("api/v1/chat", "{\"message\":\"hello\"}")).status());
        assertEquals("hello", api.lastChat);

        assertEquals(200, router.handle(post("api/v1/command", "{\"command\":\"/spawn\"}")).status());
        assertEquals("/spawn", api.lastCommand);
    }

    @Test
    @DisplayName("an unknown path is a 404 and a known path with the wrong method is a 405")
    void notFoundAndMethodNotAllowed() {
        CompanionRouter router = router(new FakeCompanionApi());

        assertEquals(404, router.handle(get("api/v1/nope")).status());
        assertEquals(405, router.handle(post("api/v1/snapshot", "")).status());
    }

    @Test
    @DisplayName("a query runs and receives its parameters, minus the token")
    void namedQuery() {
        FakeCompanionApi api = new FakeCompanionApi();
        CompanionRouter router = router(api);

        CompanionRouter.Request request = new CompanionRouter.Request(
            "GET", "api/v1/query/messages",
            Map.of("token", TOKEN, "limit", "20", "player", "Steve"),
            null, null, "127.0.0.1");
        CompanionRouter.Response response = router.handle(request);

        assertEquals(200, response.status());
        assertTrue(response.body().contains("\"limit\":\"20\""));
        assertTrue(response.body().contains("\"player\":\"Steve\""));
        assertFalse(response.body().contains(TOKEN), "the token must never be echoed back");
    }

    @Test
    @DisplayName("the rate limiter refuses a sustained flood")
    void rateLimited() {
        CompanionConfig config = config();
        config.setRequestsPerMinute(10);
        CompanionRouter router = new CompanionRouter(config, new FakeCompanionApi(),
            CompanionClientThread.direct(), new CompanionEventBus(10), "<html>");

        int refused = 0;
        for (int i = 0; i < 40; i++) {
            if (router.handle(get("api/v1/snapshot")).status() == 429) refused++;
        }
        assertTrue(refused > 0, "a flood must be throttled");
    }

    @Test
    @DisplayName("an exception in the client thread becomes a 500 with a plain message")
    void handlerFailureIsContained() {
        CompanionApi broken = new CompanionApi() {
            @Override
            public CompanionSnapshot snapshot() {
                throw new IllegalStateException("C:\\Users\\secret\\path exploded");
            }

            @Override
            public ActionResult startBot() {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult pauseBot() {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult resumeBot() {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult stopBot() {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult emergencyStopBot() {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult enqueueTask(String objective) {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult cancelTask(String taskId) {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult setModule(String name, boolean active) {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult sendChat(String message) {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult sendCommand(String command) {
                return ActionResult.ok("");
            }

            @Override
            public ActionResult notify(String message) {
                return ActionResult.ok("");
            }

            @Override
            public Map<String, Object> query(String name, Map<String, String> params) {
                return Map.of();
            }

            @Override
            public List<String> queries() {
                return List.of();
            }
        };
        CompanionRouter router = new CompanionRouter(config(), broken, CompanionClientThread.direct(),
            new CompanionEventBus(10), "<html>");

        CompanionRouter.Response response = router.handle(get("api/v1/snapshot"));

        assertEquals(500, response.status());
        assertTrue(response.body().contains("failed"), "the user gets a plain explanation");
        assertFalse(response.body().contains("secret"), "an internal path must not leak");
    }

    @Test
    @DisplayName("a client thread that never answers becomes a 504, not a hang")
    void clientThreadTimeout() {
        CompanionClientThread stalled = (task, timeoutMs) -> {
            throw new CompanionClientThread.CompanionTimeoutException("timed out");
        };
        CompanionRouter router = new CompanionRouter(config(), new FakeCompanionApi(), stalled,
            new CompanionEventBus(10), "<html>");

        CompanionRouter.Response response = router.handle(get("api/v1/snapshot"));

        assertEquals(504, response.status());
        assertTrue(response.body().contains("did not respond"));
    }

    @Test
    @DisplayName("the event stream endpoint is authenticated and advertises the SSE content type")
    void eventsEndpoint() {
        CompanionRouter router = router(new FakeCompanionApi());

        assertEquals(401, router.handle(unauthenticated("GET", "api/v1/events")).status());

        CompanionRouter.Response response = router.handle(get("api/v1/events"));
        assertEquals(200, response.status());
        assertEquals(CompanionRouter.eventStreamContentType(), response.contentType());
    }

    @Test
    @DisplayName("the health probe reports the api version and never the token")
    void health() {
        CompanionRouter router = router(new FakeCompanionApi());

        CompanionRouter.Response response = router.handle(unauthenticated("GET", "health"));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("\"api\":\"12\""));
        assertFalse(response.body().contains(TOKEN));
    }

    @Test
    @DisplayName("path normalisation and query parsing are tolerant")
    void parsing() {
        assertEquals("api/v1/snapshot", CompanionRouter.normalizePath("/api/v1/snapshot/"));
        assertEquals("api/v1/snapshot", CompanionRouter.normalizePath("api/v1/snapshot?token=x"));
        assertEquals("", CompanionRouter.normalizePath("/"));

        Map<String, String> query = CompanionRouter.parseQuery(
            java.net.URI.create("http://x/a?limit=5&name=Steve%20Jobs&flag"));
        assertEquals("5", query.get("limit"));
        assertEquals("Steve Jobs", query.get("name"));
        assertEquals("", query.get("flag"));
    }

    @Test
    @DisplayName("the config redacts its token and clamps an out-of-range port")
    void configSafety() {
        CompanionConfig config = new CompanionConfig();
        config.setToken("super-secret-value");
        assertFalse(config.toString().contains("super-secret-value"), "the token must never be printed");

        config.setPort(999999);
        assertTrue(config.port() <= 65535);
        config.setPort(-4);
        assertTrue(config.port() >= 1);

        assertNotNull(CompanionConfig.generateToken());
        assertFalse(CompanionConfig.generateToken().equals(CompanionConfig.generateToken()));
    }
}
