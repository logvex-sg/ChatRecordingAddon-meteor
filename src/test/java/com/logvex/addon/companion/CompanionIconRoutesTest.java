package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionIconCache;
import com.logvex.addon.companion.core.CompanionIconSource;
import com.logvex.addon.companion.core.CompanionRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item icon endpoint and the cache in front of it.
 *
 * <p>The fake source records what it was asked and can be told which items have no icon, so these
 * tests pin the honest-answer contract: an unknown item is a 404 the app can fall back from, a
 * client with no icon support is a 503 rather than a broken image, and a repeated request does not
 * reach the game twice.
 */
class CompanionIconRoutesTest {
    private static final String TOKEN = "test-token-abcdefghijklmnop";
    /** A one-pixel PNG is not needed; the route must pass bytes through unchanged. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    /** An icon source with two known items and call recording. */
    private static final class FakeIconSource implements CompanionIconSource {
        final List<String> calls = new ArrayList<>();

        @Override
        public byte[] icon(String itemId) {
            calls.add(itemId);
            if ("minecraft:diamond".equals(itemId) || "minecraft:stone".equals(itemId)) return PNG;
            return null;
        }
    }

    private CompanionRouter router(CompanionIconSource source) {
        CompanionConfig config = new CompanionConfig();
        config.setToken(TOKEN);
        return new CompanionRouter(config, new FakeCompanionApi(), CompanionClientThread.direct(),
            new CompanionEventBus(50), "<html>app</html>", null, source);
    }

    private static CompanionRouter.Request request(String path) {
        return new CompanionRouter.Request("GET", path, Map.of(), "Bearer " + TOKEN, null, "127.0.0.1");
    }

    @Test
    @DisplayName("a known item's icon is served as PNG bytes")
    void iconIsPng() {
        FakeIconSource source = new FakeIconSource();
        CompanionRouter router = router(source);

        CompanionRouter.Response response = router.handle(request("api/v1/items/minecraft:diamond/icon"));

        assertEquals(200, response.status());
        assertEquals("image/png", response.contentType());
        assertNotNull(response.binary(), "an icon must travel as bytes");
        assertEquals(PNG.length, response.binary().length);
        assertEquals(PNG[1], response.binary()[1], "the bytes must be the texture, not a re-encoding");
        assertEquals(List.of("minecraft:diamond"), source.calls);
    }

    @Test
    @DisplayName("an item with no icon is a 404, not a placeholder image")
    void unknownItemIs404() {
        FakeIconSource source = new FakeIconSource();
        CompanionRouter router = router(source);

        CompanionRouter.Response response = router.handle(request("api/v1/items/minecraft:not_a_real_item/icon"));

        // A placeholder would put a wrong picture on screen and call it the item.
        assertEquals(404, response.status());
        assertNull(response.binary());
        assertTrue(response.body().contains("No icon"), response.body());
    }

    @Test
    @DisplayName("a client with no icon support answers 503 instead of a broken image")
    void noSourceIs503() {
        CompanionConfig config = new CompanionConfig();
        config.setToken(TOKEN);
        CompanionRouter router = new CompanionRouter(config, new FakeCompanionApi(),
            CompanionClientThread.direct(), new CompanionEventBus(50), "<html>app</html>", null, null);

        CompanionRouter.Response response = router.handle(request("api/v1/items/minecraft:diamond/icon"));

        assertEquals(503, response.status());
    }

    @Test
    @DisplayName("the icon endpoint is not public")
    void iconNeedsAuth() {
        CompanionRouter router = router(new FakeIconSource());
        CompanionRouter.Request anonymous = new CompanionRouter.Request(
            "GET", "api/v1/items/minecraft:diamond/icon", Map.of(), null, null, "127.0.0.1");

        CompanionRouter.Response response = router.handle(anonymous);

        assertEquals(401, response.status(), "an icon is client data like anything else");
        assertNull(response.binary());
    }

    @Test
    @DisplayName("a namespaced item id with a colon reaches the source intact")
    void namespacedIdIsDecoded() {
        FakeIconSource source = new FakeIconSource();
        CompanionRouter router = router(source);

        // The app sends the colon percent-encoded, which is the only form that is safe in a path.
        router.handle(request("api/v1/items/minecraft%3Adiamond/icon"));

        assertEquals(List.of("minecraft:diamond"), source.calls);
    }

    // ------------------------------------------------------------------ cache

    @Test
    @DisplayName("the cache answers a repeat request without reaching the game again")
    void cacheServesRepeats() {
        FakeIconSource source = new FakeIconSource();
        CompanionIconCache cache = new CompanionIconCache(source, 16);

        assertNotNull(cache.icon("minecraft:diamond"));
        assertNotNull(cache.icon("minecraft:diamond"));
        assertNotNull(cache.icon("minecraft:diamond"));

        assertEquals(List.of("minecraft:diamond"), source.calls,
            "the game must be asked once per item, not once per refresh");
    }

    @Test
    @DisplayName("an item with no icon is remembered as having none, so it is not retried forever")
    void cacheRemembersMissingIcons() {
        FakeIconSource source = new FakeIconSource();
        CompanionIconCache cache = new CompanionIconCache(source, 16);

        assertNull(cache.icon("minecraft:not_a_real_item"));
        assertNull(cache.icon("minecraft:not_a_real_item"));

        assertEquals(1, source.calls.size(),
            "a missing icon is cached too, or the app re-asks every three seconds forever");
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("the cache is bounded, so a large modpack cannot grow it without limit")
    void cacheIsBounded() {
        FakeIconSource source = new FakeIconSource();
        CompanionIconCache cache = new CompanionIconCache(source, 3);

        for (int i = 0; i < 10; i++) cache.icon("item:" + i);

        assertEquals(3, cache.size());
        assertEquals(3, cache.capacity());
    }

    @Test
    @DisplayName("a null or blank item id is not a cache entry")
    void cacheIgnoresEmptyIds() {
        FakeIconSource source = new FakeIconSource();
        CompanionIconCache cache = new CompanionIconCache(source, 4);

        assertNull(cache.icon(null));
        assertNull(cache.icon("  "));

        assertEquals(0, cache.size());
        assertTrue(source.calls.isEmpty(), "an empty id must not reach the game at all");
    }

    @Test
    @DisplayName("a cache capacity below one is rejected rather than silently unbounded")
    void cacheRejectsZeroCapacity() {
        FakeIconSource source = new FakeIconSource();
        boolean threw = false;
        try {
            new CompanionIconCache(source, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "a zero capacity would mean an unbounded map");
    }
}
