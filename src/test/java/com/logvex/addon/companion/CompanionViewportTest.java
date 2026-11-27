package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionControlTarget;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionRouter;
import com.logvex.addon.companion.core.CompanionViewportSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PrismView: the live frame route and the remote-control routes.
 *
 * <p>Two things are being protected here. A frame route must never serve a frame when the client
 * cannot produce one, because a blank picture is indistinguishable from a frozen game. And a control
 * route must refuse everything while remote control is off, because that switch is the only thing
 * standing between a viewer and the player's character.
 */
class CompanionViewportTest {
    private static final String TOKEN = "test-token-abcdefghijklmnop";

    private CompanionConfig config() {
        CompanionConfig config = new CompanionConfig();
        config.setToken(TOKEN);
        return config;
    }

    private CompanionRouter router(FakeCompanionApi api, CompanionViewportSource viewport,
                                    CompanionControlTarget control) {
        return new CompanionRouter(config(), api, CompanionClientThread.direct(),
            new CompanionEventBus(50), "<html>app</html>", null, null, viewport, control);
    }

    private static CompanionRouter.Request get(String path) {
        return get(path, Map.of());
    }

    private static CompanionRouter.Request get(String path, Map<String, String> query) {
        return new CompanionRouter.Request("GET", path, query, "Bearer " + TOKEN, null, "127.0.0.1");
    }

    private static CompanionRouter.Request post(String path, String body) {
        return new CompanionRouter.Request("POST", path, Map.of(), "Bearer " + TOKEN, body, "127.0.0.1");
    }

    /** A viewport that hands back a fixed frame, so the route can be checked without a renderer. */
    static final class FakeViewport implements CompanionViewportSource {
        boolean available = true;
        int lastWidth;
        int lastQuality;
        byte[] bytes = new byte[]{1, 2, 3, 4};

        @Override
        public Frame capture(int targetWidth, int quality) {
            lastWidth = targetWidth;
            lastQuality = quality;
            return new Frame(bytes, 320, 180, System.currentTimeMillis());
        }

        @Override
        public int maxWidth() {
            return 1920;
        }

        @Override
        public int minWidth() {
            return 160;
        }

        @Override
        public boolean available() {
            return available;
        }
    }

    /** A control target that records calls and can pretend remote control is off. */
    static final class FakeControl implements CompanionControlTarget {
        boolean enabled = true;
        List<String> held;
        double lastYaw;
        double lastPitch;
        int lastButton = -1;
        boolean lastHold;
        int lastHotbar = -1;
        int releases;

        @Override
        public String setInputs(List<String> held) {
            this.held = new ArrayList<>(held);
            return null;
        }

        @Override
        public String look(double deltaYaw, double deltaPitch) {
            lastYaw = deltaYaw;
            lastPitch = deltaPitch;
            return null;
        }

        @Override
        public String mouse(int button, boolean hold) {
            lastButton = button;
            lastHold = hold;
            return null;
        }

        @Override
        public String selectHotbar(int slot) {
            lastHotbar = slot;
            return null;
        }

        @Override
        public void releaseAll() {
            releases++;
            held = List.of();
        }

        @Override
        public boolean enabled() {
            return enabled;
        }
    }

    @Test
    @DisplayName("a frame is served as an image with the quality the caller asked for")
    void frameIsServed() {
        FakeViewport viewport = new FakeViewport();
        CompanionRouter router = router(new FakeCompanionApi(), viewport, new FakeControl());

        CompanionRouter.Response response = router.handle(get("/api/v1/viewport", Map.of("width", "640", "quality", "50")));

        assertEquals(200, response.status());
        assertEquals("image/jpeg", response.contentType(), "quality below 100 is JPEG");
        assertNotNull(response.binary());
        assertEquals(4, response.binary().length);
        assertEquals(640, viewport.lastWidth);
        assertEquals(50, viewport.lastQuality);
    }

    @Test
    @DisplayName("quality 100 means a lossless PNG, which is what a detail view needs")
    void qualityHundredIsPng() {
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), new FakeControl());

        CompanionRouter.Response response = router.handle(get("/api/v1/viewport", Map.of("quality", "100")));

        assertEquals("image/png", response.contentType());
    }

    @Test
    @DisplayName("a width above the maximum is clipped, so one request cannot force a huge capture")
    void widthIsClipped() {
        FakeViewport viewport = new FakeViewport();
        CompanionRouter router = router(new FakeCompanionApi(), viewport, new FakeControl());

        router.handle(get("/api/v1/viewport", Map.of("width", "99999")));

        assertEquals(1920, viewport.lastWidth, "the request is clipped to the source's maximum");
    }

    @Test
    @DisplayName("a width below the minimum is raised, so a viewer cannot ask for a useless stream")
    void widthIsRaised() {
        FakeViewport viewport = new FakeViewport();
        CompanionRouter router = router(new FakeCompanionApi(), viewport, new FakeControl());

        router.handle(get("/api/v1/viewport", Map.of("width", "1")));

        assertEquals(160, viewport.lastWidth);
    }

    @Test
    @DisplayName("no frame is served when the client cannot produce one")
    void unavailableViewportIs503() {
        FakeViewport viewport = new FakeViewport();
        viewport.available = false;
        CompanionRouter router = router(new FakeCompanionApi(), viewport, new FakeControl());

        CompanionRouter.Response response = router.handle(get("/api/v1/viewport"));

        assertEquals(503, response.status());
        assertNull(response.binary());
        assertTrue(response.body().contains("not available"));
    }

    @Test
    @DisplayName("the info route reports the bounds and whether control is on")
    void infoReportsBounds() {
        FakeControl control = new FakeControl();
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        CompanionRouter.Response response = router.handle(get("/api/v1/viewport/info"));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("\"available\":true"));
        assertTrue(response.body().contains("\"maxWidth\":1920"));
        assertTrue(response.body().contains("\"controlEnabled\":true"));
    }

    @Test
    @DisplayName("holding inputs reaches the client with the exact names sent")
    void inputsAreForwarded() {
        FakeControl control = new FakeControl();
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        CompanionRouter.Response response = router.handle(post("/api/v1/control/inputs",
            "{\"held\":[\"forward\",\"sprint\"]}"));

        assertEquals(200, response.status());
        assertEquals(List.of("forward", "sprint"), control.held);
    }

    @Test
    @DisplayName("an empty held list stops the player, which is the important default")
    void emptyInputsStop() {
        FakeControl control = new FakeControl();
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        router.handle(post("/api/v1/control/inputs", "{\"held\":[]}"));

        assertTrue(control.held.isEmpty());
    }

    @Test
    @DisplayName("a control request is refused while remote control is off")
    void controlRefusedWhenDisabled() {
        FakeControl control = new FakeControl();
        control.enabled = false;
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        assertEquals(403, router.handle(post("/api/v1/control/inputs",
            "{\"held\":[\"forward\"]}")).status());
        assertEquals(403, router.handle(post("/api/v1/control/look",
            "{\"yaw\":10,\"pitch\":0}")).status());
        assertEquals(403, router.handle(post("/api/v1/control/mouse",
            "{\"button\":0,\"hold\":true}")).status());
        assertEquals(403, router.handle(post("/api/v1/control/hotbar",
            "{\"slot\":3}")).status());
        assertNull(control.held, "nothing must be applied while control is off");
        assertEquals(-1, control.lastButton);
    }

    @Test
    @DisplayName("releasing is allowed even when control is off, because it makes the client safer")
    void releaseAlwaysAllowed() {
        FakeControl control = new FakeControl();
        control.enabled = false;
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        CompanionRouter.Response response = router.handle(post("/api/v1/control/release", "{}"));

        assertEquals(200, response.status());
        assertEquals(1, control.releases);
    }

    @Test
    @DisplayName("a look request without both angles is refused rather than treated as zero")
    void lookNeedsBothAngles() {
        FakeControl control = new FakeControl();
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        assertEquals(400, router.handle(post("/api/v1/control/look", "{\"yaw\":10}")).status());
        assertEquals(400, router.handle(post("/api/v1/control/look", "{}")).status());
    }

    @Test
    @DisplayName("a look request reaches the client with its exact angles")
    void lookIsForwarded() {
        FakeControl control = new FakeControl();
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        CompanionRouter.Response response = router.handle(post("/api/v1/control/look",
            "{\"yaw\":-12.5,\"pitch\":8}"));

        assertEquals(200, response.status());
        assertEquals(-12.5, control.lastYaw, 0.0001);
        assertEquals(8.0, control.lastPitch, 0.0001);
    }

    @Test
    @DisplayName("a hotbar request without a slot is refused")
    void hotbarNeedsSlot() {
        FakeControl control = new FakeControl();
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        assertEquals(400, router.handle(post("/api/v1/control/hotbar", "{}")).status());
        assertEquals(-1, control.lastHotbar);
    }

    @Test
    @DisplayName("a mouse request carries its button and hold state")
    void mouseIsForwarded() {
        FakeControl control = new FakeControl();
        CompanionRouter router = router(new FakeCompanionApi(), new FakeViewport(), control);

        router.handle(post("/api/v1/control/mouse", "{\"button\":1,\"hold\":true}"));

        assertEquals(1, control.lastButton);
        assertTrue(control.lastHold);
    }

    @Test
    @DisplayName("the seven movement names are the only ones offered")
    void inputNamesAreFixed() {
        List<String> names = CompanionControlTarget.inputNames();

        assertEquals(7, names.size());
        assertTrue(names.contains("forward"));
        assertTrue(names.contains("sprint"));
        assertFalse(names.contains("chat"), "chat must never be a movement input");
    }
}
