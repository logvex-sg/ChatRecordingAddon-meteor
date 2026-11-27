package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionMapSource;
import com.logvex.addon.companion.core.CompanionRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map, waypoint and telemetry endpoints.
 *
 * <p>The fake source holds data shaped exactly like the real one and records what it was asked, so
 * these tests check the routing contract (status codes, parameter passing, binary bodies) rather than
 * a mock's expectations. The point of each test is the honest answer for an edge case: unexplored
 * terrain is a 404, a missing map service is a 503, and a waypoint with no name is rejected.
 */
class CompanionMapRoutesTest {
    private static final String TOKEN = "test-token-abcdefghijklmnop";

    /** A source with one captured tile and one waypoint, plus call recording. */
    private static final class FakeMapSource implements CompanionMapSource {
        final List<String> calls = new ArrayList<>();
        final byte[] tile = {1, 2, 3, 4, 5};
        boolean inWorld = true;
        boolean removeResult = true;

        @Override
        public Map<String, Object> terrainMeta() {
            calls.add("terrainMeta");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("totalChunks", 4);
            out.put("dimensions", List.of(Map.of("dimension", "minecraft:overworld", "chunks", 4)));
            return out;
        }

        @Override
        public byte[] terrainTile(String dimension, int tileX, int tileZ) {
            calls.add("tile:" + dimension + ":" + tileX + ":" + tileZ);
            return tileX == 99 ? null : tile;
        }

        @Override
        public List<Map<String, Object>> waypoints(String dimension) {
            calls.add("waypoints:" + dimension);
            return List.of(Map.of("key", "overworld:1:64:2", "name", "Home"));
        }

        @Override
        public Map<String, Object> addWaypoint(String name, String category, Integer x, Integer y, Integer z) {
            calls.add("add:" + name + ":" + category + ":" + x + ":" + y + ":" + z);
            if (!inWorld) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", "overworld:1:64:2");
            out.put("name", name);
            return out;
        }

        @Override
        public boolean removeWaypoint(String key) {
            calls.add("remove:" + key);
            return removeResult;
        }

        @Override
        public Map<String, Object> telemetry() {
            calls.add("telemetry");
            return Map.of("series", Map.of("fps", Map.of("latest", 60)), "recording", true);
        }

        @Override
        public String telemetryCsv() {
            calls.add("csv");
            return "metric,timestamp,value\nfps,1000,60\n";
        }
    }

    private CompanionRouter router(CompanionMapSource source) {
        CompanionConfig config = new CompanionConfig();
        config.setToken(TOKEN);
        return new CompanionRouter(config, new FakeCompanionApi(), CompanionClientThread.direct(),
            new CompanionEventBus(50), "<html>app</html>", source);
    }

    private static CompanionRouter.Request request(String method, String path, Map<String, String> query) {
        return new CompanionRouter.Request(method, path, query, "Bearer " + TOKEN, null, "127.0.0.1");
    }

    @Test
    @DisplayName("terrain metadata is served as JSON")
    void terrainMetaIsJson() {
        CompanionRouter router = router(new FakeMapSource());
        CompanionRouter.Response response = router.handle(request("GET", "api/v1/terrain", Map.of()));

        assertEquals(200, response.status());
        assertTrue(response.contentType().startsWith("application/json"));
        assertTrue(response.body().contains("\"totalChunks\":4"), response.body());
    }

    @Test
    @DisplayName("a captured tile is served as bytes, not as a JSON-wrapped string")
    void tileIsBinary() {
        FakeMapSource source = new FakeMapSource();
        CompanionRouter router = router(source);
        CompanionRouter.Response response = router.handle(request("GET", "api/v1/terrain/minecraft:overworld/3/-7", Map.of()));

        assertEquals(200, response.status());
        assertEquals("application/octet-stream", response.contentType());
        assertNotNull(response.binary(), "a tile must travel as bytes");
        assertEquals(5, response.binary().length);
        assertEquals(5, response.binary()[4], "the bytes must be the tile, not a re-encoding");
        assertTrue(source.calls.contains("tile:minecraft:overworld:3:-7"), source.calls.toString());
    }

    @Test
    @DisplayName("unexplored terrain is a 404, so the app can draw a hole instead of flat land")
    void unexploredTileIs404() {
        CompanionRouter router = router(new FakeMapSource());
        CompanionRouter.Response response = router.handle(request("GET", "api/v1/terrain/minecraft:overworld/99/0", Map.of()));

        assertEquals(404, response.status());
        assertTrue(response.body().contains("No terrain"), response.body());
    }

    @Test
    @DisplayName("a tile request with a non-numeric coordinate is rejected, not guessed at")
    void badTileCoordinateIsRejected() {
        FakeMapSource source = new FakeMapSource();
        CompanionRouter router = router(source);
        CompanionRouter.Response response = router.handle(request("GET", "api/v1/terrain/minecraft:overworld/east/0", Map.of()));

        assertEquals(400, response.status());
        assertTrue(source.calls.isEmpty(), "the source must not be asked for a tile it cannot name");
    }

    @Test
    @DisplayName("waypoints are listed, optionally filtered by dimension")
    void waypointsAreListed() {
        FakeMapSource source = new FakeMapSource();
        CompanionRouter router = router(source);
        CompanionRouter.Response response = router.handle(
            request("GET", "api/v1/waypoints", Map.of("dimension", "minecraft:overworld")));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("Home"), response.body());
        assertTrue(source.calls.contains("waypoints:minecraft:overworld"));
    }

    @Test
    @DisplayName("adding a waypoint with a name and coordinates passes them through")
    void waypointAddPassesThrough() {
        FakeMapSource source = new FakeMapSource();
        CompanionRouter router = router(source);
        Map<String, String> query = Map.of("name", "Base", "category", "home", "x", "10", "y", "64", "z", "-20");
        CompanionRouter.Response response = router.handle(request("POST", "api/v1/waypoints", query));

        assertEquals(200, response.status());
        assertTrue(source.calls.contains("add:Base:home:10:64:-20"), source.calls.toString());
    }

    @Test
    @DisplayName("a waypoint with no name is rejected before it reaches the world database")
    void waypointWithoutNameIsRejected() {
        FakeMapSource source = new FakeMapSource();
        CompanionRouter router = router(source);
        CompanionRouter.Response response = router.handle(
            request("POST", "api/v1/waypoints", Map.of("x", "1", "y", "2", "z", "3")));

        assertEquals(400, response.status());
        assertTrue(source.calls.isEmpty());
    }

    @Test
    @DisplayName("marking a waypoint outside a world is a conflict, not a silent no-op")
    void waypointOutsideWorldConflicts() {
        FakeMapSource source = new FakeMapSource();
        source.inWorld = false;
        CompanionRouter router = router(source);
        CompanionRouter.Response response = router.handle(
            request("POST", "api/v1/waypoints", Map.of("name", "Base")));

        assertEquals(409, response.status());
    }

    @Test
    @DisplayName("removing an unknown waypoint is a 404")
    void removingUnknownWaypointIs404() {
        FakeMapSource source = new FakeMapSource();
        source.removeResult = false;
        CompanionRouter router = router(source);
        CompanionRouter.Response response = router.handle(request("DELETE", "api/v1/waypoints/overworld:1:2:3", Map.of()));

        assertEquals(404, response.status());
        assertTrue(source.calls.contains("remove:overworld:1:2:3"));
    }

    @Test
    @DisplayName("telemetry is served as JSON and exported as CSV with a header")
    void telemetryAndCsv() {
        CompanionRouter router = router(new FakeMapSource());

        CompanionRouter.Response json = router.handle(request("GET", "api/v1/telemetry", Map.of()));
        assertEquals(200, json.status());
        assertTrue(json.body().contains("\"fps\""), json.body());

        CompanionRouter.Response csv = router.handle(request("GET", "api/v1/telemetry/export.csv", Map.of()));
        assertEquals(200, csv.status());
        assertEquals("text/csv; charset=utf-8", csv.contentType());
        assertTrue(csv.body().startsWith("metric,timestamp,value"));
        assertTrue(csv.body().contains("fps,1000,60"));
    }

    @Test
    @DisplayName("without a map service the map endpoints answer 503 rather than failing")
    void missingMapServiceIs503() {
        CompanionRouter router = router(null);
        for (String path : List.of("api/v1/terrain", "api/v1/waypoints", "api/v1/telemetry")) {
            CompanionRouter.Response response = router.handle(request("GET", path, Map.of()));
            assertEquals(503, response.status(), path);
            assertTrue(response.body().contains("not running"), response.body());
        }
    }

    @Test
    @DisplayName("the map endpoints are not public, so terrain cannot be read without a token")
    void mapEndpointsRequireAuth() {
        CompanionRouter router = router(new FakeMapSource());
        CompanionRouter.Request anonymous = new CompanionRouter.Request(
            "GET", "api/v1/terrain", Map.of(), null, null, "127.0.0.1");

        assertEquals(401, router.handle(anonymous).status());
    }

    @Test
    @DisplayName("a binary response writes its bytes and a text response writes its body")
    void binaryAndTextBodiesCoexist() {
        CompanionRouter.Response binary = CompanionRouter.Response.binary(200, "application/octet-stream",
            "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", new String(binary.binary(), StandardCharsets.UTF_8));
        assertEquals("", binary.body(), "a binary response must not also carry a text body");

        CompanionRouter.Response text = CompanionRouter.Response.text(200, "plain");
        assertNotNull(text.body());
        assertEquals("plain", text.body());
    }
}
