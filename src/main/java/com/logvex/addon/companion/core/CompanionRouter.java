package com.logvex.addon.companion.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The companion HTTP surface, expressed without any HTTP library.
 *
 * <p>A {@link CompanionServer} turns a socket request into a {@link Request} and hands it here; this
 * class decides what the answer is. Keeping the routing, authentication, rate limiting and every
 * endpoint free of {@code com.sun.net.httpserver} means the whole API is unit tested on a plain
 * method call, and the transport is a thin adapter that is hard to get wrong.
 *
 * <p>Routes are declared as {@code METHOD /path/with/{param}}. Handlers never throw: an unexpected
 * exception becomes a 500 with a plain message, and the technical detail is left to the caller's log
 * rather than the response body, so an error the app shows never leaks an internal path or a secret.
 */
public final class CompanionRouter {
    /** A parsed request. */
    public record Request(
        String method,
        String path,
        Map<String, String> query,
        String authorization,
        String body,
        String remoteAddress
    ) {
        public String queryToken() {
            return query.get("token");
        }
    }

    /**
     * A response: a status, a content type and a body.
     *
     * <p>{@code binary} carries a payload that is not text (a terrain tile). When it is set the
     * transport writes it verbatim and ignores {@code body}; when it is null the body is written as
     * UTF-8. The three-argument constructor keeps the ordinary text case short.
     */
    public record Response(int status, String contentType, String body, byte[] binary) {
        public Response(int status, String contentType, String body) {
            this(status, contentType, body, null);
        }

        /** A response whose body is raw bytes, such as a compressed terrain tile. */
        public static Response binary(int status, String contentType, byte[] value) {
            return new Response(status, contentType, "", value == null ? new byte[0] : value);
        }

        public static Response json(int status, Object value) {
            return new Response(status, "application/json; charset=utf-8", CompanionJson.write(value));
        }

        public static Response text(int status, String value) {
            return new Response(status, "text/plain; charset=utf-8", value);
        }

        public static Response html(String value) {
            return new Response(200, "text/html; charset=utf-8", value);
        }

        public static Response noContent() {
            return new Response(204, "text/plain; charset=utf-8", "");
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    private record Route(String method, String[] segments, boolean[] wildcards, Handler handler) {
    }

    private final CompanionConfig config;
    private final CompanionAuth auth;
    private final CompanionRateLimiter rateLimiter;
    private final CompanionApi api;
    private final CompanionClientThread clientThread;
    private final CompanionEventBus events;
    private final String appHtml;
    private final CompanionMapSource mapSource;
    private final CompanionIconSource iconSource;
    private final CompanionViewportSource viewportSource;
    private final CompanionControlTarget controlTarget;
    private final List<Route> routes = new ArrayList<>();

    public CompanionRouter(
        CompanionConfig config,
        CompanionApi api,
        CompanionClientThread clientThread,
        CompanionEventBus events,
        String appHtml
    ) {
        this(config, api, clientThread, events, appHtml, null);
    }

    /**
     * The full constructor, with the map and telemetry source.
     *
     * <p>{@code mapSource} may be null, in which case the map endpoints answer "not available"
     * instead of failing. That keeps the router usable in a test or on a build where the terrain
     * service was not started.
     */
    public CompanionRouter(
        CompanionConfig config,
        CompanionApi api,
        CompanionClientThread clientThread,
        CompanionEventBus events,
        String appHtml,
        CompanionMapSource mapSource
    ) {
        this(config, api, clientThread, events, appHtml, mapSource, null);
    }

    /**
     * The full constructor, with an icon source as well.
     *
     * <p>{@code iconSource} may be null, in which case item icons answer "not available" and the app
     * shows item names. That keeps the router usable in tests and on a build with no client.
     */
    public CompanionRouter(
        CompanionConfig config,
        CompanionApi api,
        CompanionClientThread clientThread,
        CompanionEventBus events,
        String appHtml,
        CompanionMapSource mapSource,
        CompanionIconSource iconSource
    ) {
        this(config, api, clientThread, events, appHtml, mapSource, iconSource, null, null);
    }

    /**
     * The complete constructor, adding live video and remote control.
     *
     * <p>Both may be null, in which case the viewport answers "not available" and every control
     * request is refused with a plain reason. A build without a renderer, or a client whose owner has
     * not enabled remote control, is therefore a working app with two features switched off rather
     * than a broken one.
     */
    public CompanionRouter(
        CompanionConfig config,
        CompanionApi api,
        CompanionClientThread clientThread,
        CompanionEventBus events,
        String appHtml,
        CompanionMapSource mapSource,
        CompanionIconSource iconSource,
        CompanionViewportSource viewportSource,
        CompanionControlTarget controlTarget
    ) {
        this.config = config;
        this.api = api;
        this.clientThread = clientThread;
        this.events = events;
        this.appHtml = appHtml;
        this.mapSource = mapSource;
        this.iconSource = iconSource;
        this.viewportSource = viewportSource;
        this.controlTarget = controlTarget;
        this.auth = new CompanionAuth(config);
        this.rateLimiter = new CompanionRateLimiter(config.requestsPerMinute());
        registerRoutes();
    }

    public CompanionRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * Whether a request may proceed without a token, i.e. whether it is the app shell or a health
     * probe. Both are needed before the UI has a token to send.
     */
    public static boolean isPublicPath(String path) {
        String normalised = normalizePath(path);
        return normalised.isEmpty() || normalised.equals("index.html") || normalised.equals("health");
    }

    /**
     * Checks a request against the access token.
     *
     * <p>Exposed so a caller that answers a request without going through {@link #handle} can apply
     * the same rule. The event stream is one: it is held open by the transport, so it never reaches
     * the router's own dispatch and would otherwise be served to anyone who could connect.
     */
    public boolean isAuthorized(Request request) {
        if (request == null) return false;
        return auth.isAuthorized(request.authorization(), request.queryToken());
    }

    /**
     * An error response in the shape every endpoint uses, so a caller that answers a request itself
     * returns the same body as {@link #handle} would instead of a bare JSON string.
     */
    public Response errorResponse(int status, String message) {
        return Response.json(status, error(message));
    }

    public CompanionEventBus events() {
        return events;
    }

    // ------------------------------------------------------------------ routing

    private void registerRoutes() {
        // The app shell is public: it contains no data, only the code that asks for it with a token.
        route("GET", "/", request -> Response.html(appHtml));
        route("GET", "/index.html", request -> Response.html(appHtml));
        route("GET", "/health", request -> Response.json(200, health()));

        route("GET", "/api/v1/snapshot", request -> Response.json(200, call(api::snapshot)));
        route("GET", "/api/v1/modules", request -> Response.json(200, call(api::snapshot).modules()));
        route("GET", "/api/v1/tasks", request -> Response.json(200, call(api::snapshot).tasks()));
        route("GET", "/api/v1/players", request -> Response.json(200, call(api::snapshot).players()));
        route("GET", "/api/v1/chat", request -> Response.json(200, call(api::snapshot).chat()));
        route("GET", "/api/v1/bot", request -> Response.json(200, call(api::snapshot).bot()));
        route("GET", "/api/v1/world", request -> Response.json(200, call(api::snapshot).world()));
        route("GET", "/api/v1/storage", request -> Response.json(200, call(api::snapshot).storage()));
        route("GET", "/api/v1/inventory", request -> Response.json(200, call(api::snapshot).inventory()));
        route("GET", "/api/v1/inventory/actions", request -> Response.json(200, api.slotActions()));
        route("GET", "/api/v1/queries", request -> Response.json(200, api.queries()));

        route("GET", "/api/v1/events", this::eventsResponse);

        route("POST", "/api/v1/bot/start", request -> action(api::startBot));
        route("POST", "/api/v1/bot/pause", request -> action(api::pauseBot));
        route("POST", "/api/v1/bot/resume", request -> action(api::resumeBot));
        route("POST", "/api/v1/bot/stop", request -> action(api::stopBot));
        route("POST", "/api/v1/bot/emergency-stop", request -> action(api::emergencyStopBot));

        route("POST", "/api/v1/tasks", request -> {
            JsonObject body = CompanionJson.parseObject(request.body());
            String objective = CompanionJson.string(body, "objective");
            if (objective == null || objective.isBlank()) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("No objective was given.")));
            }
            return Response.json(200, CompanionJson.result(call(() -> api.enqueueTask(objective))));
        });

        route("DELETE", "/api/v1/tasks/{id}", request -> {
            String id = request.query().get("__id");
            return Response.json(200, CompanionJson.result(call(() -> api.cancelTask(id))));
        });

        route("POST", "/api/v1/modules/{name}", request -> {
            JsonObject body = CompanionJson.parseObject(request.body());
            boolean active = CompanionJson.bool(body, "active", true);
            String name = request.query().get("__name");
            return Response.json(200, CompanionJson.result(call(() -> api.setModule(name, active))));
        });

        // Settings are read and written one module at a time, not all at once: a client has over a
        // hundred modules and several thousand settings between them, and the app only ever shows
        // one module's settings.
        route("GET", "/api/v1/modules/{name}/settings", request -> {
            String name = request.query().get("__name");
            List<CompanionSnapshot.SettingView> settings = call(() -> api.moduleSettings(name));
            if (settings == null) {
                return Response.json(404, error("No module is called \"" + name + "\"."));
            }
            Map<String, Object> body = CompanionJson.map();
            body.put("ok", true);
            body.put("module", name);
            body.put("settings", settings);
            return Response.json(200, body);
        });

        route("POST", "/api/v1/modules/{name}/settings", request -> {
            String moduleName = request.query().get("__name");
            JsonObject body = CompanionJson.parseObject(request.body());
            String settingName = CompanionJson.string(body, "setting");
            if (settingName == null || settingName.isBlank()) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("No setting was named.")));
            }
            // A present-but-null value is as unusable as a missing one, so both are refused here
            // rather than being passed down as "set it to null".
            JsonElement value = body.has("value") ? body.get("value") : null;
            if (value == null || value.isJsonNull()) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("No value was given.")));
            }
            return Response.json(200, CompanionJson.result(
                call(() -> api.setModuleSetting(moduleName, settingName, value))));
        });

        route("POST", "/api/v1/chat", request -> {
            JsonObject body = CompanionJson.parseObject(request.body());
            String message = CompanionJson.string(body, "message");
            if (message == null || message.isBlank()) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("The message was empty.")));
            }
            return Response.json(200, CompanionJson.result(call(() -> api.sendChat(message))));
        });

        route("POST", "/api/v1/command", request -> {
            JsonObject body = CompanionJson.parseObject(request.body());
            String command = CompanionJson.string(body, "command");
            if (command == null || command.isBlank()) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("The command was empty.")));
            }
            return Response.json(200, CompanionJson.result(call(() -> api.sendCommand(command))));
        });

        route("POST", "/api/v1/notify", request -> {
            JsonObject body = CompanionJson.parseObject(request.body());
            String message = CompanionJson.string(body, "message");
            if (message == null || message.isBlank()) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("The message was empty.")));
            }
            return Response.json(200, CompanionJson.result(call(() -> api.notify(message))));
        });

        // ---------------------------------------------------------------- inventory actions
        // The click body is validated here rather than in the Minecraft layer so a malformed request
        // is refused before it can reach the game thread at all. The handler still re-checks the sync
        // id and the slot range, because only it knows what is open by the time the click runs.
        route("POST", "/api/v1/inventory/click", request -> {
            JsonObject body = CompanionJson.parseObject(request.body());
            SlotAction action = SlotAction.fromWire(CompanionJson.string(body, "action"));
            if (action == null) {
                return Response.json(400, CompanionJson.result(CompanionApi.ActionResult.fail(
                    "An inventory click needs an action: " + String.join(", ", SlotAction.wireNames()) + ".")));
            }
            Integer slot = CompanionJson.integer(body, "slot", null);
            if (slot == null) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("An inventory click needs a slot.")));
            }
            int button = CompanionJson.integer(body, "button", 0);
            if (button < 0 || button > 8) {
                return Response.json(400, CompanionJson.result(CompanionApi.ActionResult.fail(
                    "A click button must be between 0 and 8.")));
            }
            // A missing sync id means "whatever is open"; a present one pins the request to the
            // handler the caller saw, which is what the app always sends.
            int syncId = CompanionJson.integer(body, "syncId", -1);
            return Response.json(200, CompanionJson.result(call(
                () -> api.clickSlot(slot, button, action.wire(), syncId))));
        });

        // ---------------------------------------------------------------- PrismView
        route("GET", "/api/v1/viewport", request -> {
            CompanionViewportSource source = viewportSource;
            if (source == null || !source.available()) return viewportUnavailable();
            Integer width = intParam(request.query().get("width"));
            Integer quality = intParam(request.query().get("quality"));
            int q = quality == null ? 80 : Math.max(1, Math.min(100, quality));
            int w = width == null ? 0 : Math.max(source.minWidth(), Math.min(source.maxWidth(), width));
            CompanionViewportSource.Frame frame = call(() -> source.capture(w, q));
            if (frame == null || frame.png() == null) return viewportUnavailable();
            // A frame is a picture of the player's screen: never let a proxy or the browser reuse one.
            return Response.binary(200, viewportContentType(q), frame.png());
        });

        route("GET", "/api/v1/viewport/info", request -> {
            CompanionViewportSource source = viewportSource;
            Map<String, Object> body = CompanionJson.map();
            body.put("available", source != null && source.available());
            body.put("minWidth", source == null ? 0 : source.minWidth());
            body.put("maxWidth", source == null ? 0 : source.maxWidth());
            body.put("controlEnabled", controlTarget != null && controlTarget.enabled());
            body.put("inputs", CompanionControlTarget.inputNames());
            return Response.json(200, body);
        });

        // ---------------------------------------------------------------- remote control
        // Every control request is refused unless the client itself has remote control enabled. The
        // switch is read per request rather than cached, so turning it off stops the very next move.
        route("POST", "/api/v1/control/inputs", request -> {
            String reason = controlUnavailable();
            if (reason != null) return controlRefused(reason);
            JsonObject body = CompanionJson.parseObject(request.body());
            List<String> held = new ArrayList<>();
            if (body != null && body.has("held") && body.get("held").isJsonArray()) {
                for (JsonElement element : body.getAsJsonArray("held")) {
                    if (element != null && element.isJsonPrimitive()) held.add(element.getAsString());
                }
            }
            String failure = call(() -> controlTarget.setInputs(held));
            return failure == null
                ? Response.json(200, CompanionJson.result(CompanionApi.ActionResult.ok(
                    held.isEmpty() ? "Stopped moving." : "Holding " + String.join(", ", held) + ".")))
                : Response.json(400, CompanionJson.result(CompanionApi.ActionResult.fail(failure)));
        });

        route("POST", "/api/v1/control/look", request -> {
            String reason = controlUnavailable();
            if (reason != null) return controlRefused(reason);
            JsonObject body = CompanionJson.parseObject(request.body());
            Double yaw = CompanionJson.number(body, "yaw");
            Double pitch = CompanionJson.number(body, "pitch");
            if (yaw == null || pitch == null) {
                return Response.json(400, CompanionJson.result(CompanionApi.ActionResult.fail(
                    "A look request needs yaw and pitch in degrees.")));
            }
            String failure = call(() -> controlTarget.look(yaw, pitch));
            return failure == null
                ? Response.json(200, CompanionJson.result(CompanionApi.ActionResult.ok("Looked.")))
                : Response.json(400, CompanionJson.result(CompanionApi.ActionResult.fail(failure)));
        });

        route("POST", "/api/v1/control/mouse", request -> {
            String reason = controlUnavailable();
            if (reason != null) return controlRefused(reason);
            JsonObject body = CompanionJson.parseObject(request.body());
            Integer button = CompanionJson.integer(body, "button", 0);
            boolean hold = CompanionJson.bool(body, "hold", false);
            String failure = call(() -> controlTarget.mouse(button, hold));
            return failure == null
                ? Response.json(200, CompanionJson.result(CompanionApi.ActionResult.ok(
                    (hold ? "Holding" : "Released") + " mouse " + button + ".")))
                : Response.json(400, CompanionJson.result(CompanionApi.ActionResult.fail(failure)));
        });

        route("POST", "/api/v1/control/hotbar", request -> {
            String reason = controlUnavailable();
            if (reason != null) return controlRefused(reason);
            JsonObject body = CompanionJson.parseObject(request.body());
            Integer slot = CompanionJson.integer(body, "slot", null);
            if (slot == null) {
                return Response.json(400, CompanionJson.result(
                    CompanionApi.ActionResult.fail("A hotbar request needs a slot.")));
            }
            String failure = call(() -> controlTarget.selectHotbar(slot));
            return failure == null
                ? Response.json(200, CompanionJson.result(CompanionApi.ActionResult.ok(
                    "Selected hotbar slot " + (slot + 1) + ".")))
                : Response.json(400, CompanionJson.result(CompanionApi.ActionResult.fail(failure)));
        });

        // Releasing everything is always allowed, even when control has just been switched off: it is
        // the one request that makes the client safer, so refusing it would be backwards.
        route("POST", "/api/v1/control/release", request -> {
            if (controlTarget != null) call(() -> {
                controlTarget.releaseAll();
                return null;
            });
            return Response.json(200, CompanionJson.result(
                CompanionApi.ActionResult.ok("All inputs released.")));
        });

        // ---------------------------------------------------------------- map and telemetry
        route("GET", "/api/v1/terrain", request -> {
            CompanionMapSource source = mapSource;
            if (source == null) return mapUnavailable();
            return Response.json(200, source.terrainMeta());
        });

        route("GET", "/api/v1/terrain/{dimension}/{tileX}/{tileZ}", request -> {
            CompanionMapSource source = mapSource;
            if (source == null) return mapUnavailable();
            String dimension = request.query().get("__dimension");
            Integer tileX = intParam(request.query().get("__tileX"));
            Integer tileZ = intParam(request.query().get("__tileZ"));
            if (dimension == null || dimension.isBlank() || tileX == null || tileZ == null) {
                return Response.json(400, error("A tile request needs a dimension and two tile coordinates."));
            }
            byte[] tile = call(() -> source.terrainTile(dimension, tileX, tileZ));
            if (tile == null) {
                // 404 is the honest answer for unexplored terrain; the app draws a hole, not sea level.
                return Response.json(404, error("No terrain has been captured for that tile."));
            }
            return Response.binary(200, "application/octet-stream", tile);
        });

        route("GET", "/api/v1/items/{item}/icon", request -> {
            CompanionIconSource source = iconSource;
            if (source == null) {
                return Response.json(503, error("Item icons are not available on this client."));
            }
            String item = request.query().get("__item");
            if (item == null || item.isBlank()) {
                return Response.json(400, error("An icon request needs an item id."));
            }
            byte[] png = call(() -> source.icon(item));
            if (png == null) {
                // An unknown item has no icon; the app keeps the item's name on screen.
                return Response.json(404, error("No icon for that item."));
            }
            return Response.binary(200, "image/png", png);
        });

        route("GET", "/api/v1/waypoints", request -> {
            CompanionMapSource source = mapSource;
            if (source == null) return mapUnavailable();
            String dimension = request.query().get("dimension");
            return Response.json(200, call(() -> source.waypoints(dimension)));
        });

        route("POST", "/api/v1/waypoints", request -> {
            CompanionMapSource source = mapSource;
            if (source == null) return mapUnavailable();
            String name = request.query().get("name");
            if (name == null || name.isBlank()) {
                return Response.json(400, error("A waypoint needs a name."));
            }
            String category = request.query().get("category");
            Integer x = intParam(request.query().get("x"));
            Integer y = intParam(request.query().get("y"));
            Integer z = intParam(request.query().get("z"));
            Map<String, Object> stored = call(() -> source.addWaypoint(name, category, x, y, z));
            if (stored == null) {
                return Response.json(409, error("The client is not in a world, so there is no place to mark."));
            }
            return Response.json(200, stored);
        });

        route("DELETE", "/api/v1/waypoints/{key}", request -> {
            CompanionMapSource source = mapSource;
            if (source == null) return mapUnavailable();
            String key = request.query().get("__key");
            if (key == null || key.isBlank()) {
                return Response.json(400, error("A waypoint key is required."));
            }
            boolean removed = call(() -> source.removeWaypoint(key));
            if (!removed) return Response.json(404, error("No waypoint has that key."));
            return Response.json(200, CompanionJson.result(CompanionApi.ActionResult.ok("Waypoint removed.")));
        });

        route("GET", "/api/v1/telemetry", request -> {
            CompanionMapSource source = mapSource;
            if (source == null) return mapUnavailable();
            return Response.json(200, call(source::telemetry));
        });

        route("GET", "/api/v1/telemetry/export.csv", request -> {
            CompanionMapSource source = mapSource;
            if (source == null) return mapUnavailable();
            String csv = call(source::telemetryCsv);
            return new Response(200, "text/csv; charset=utf-8", csv == null ? "" : csv);
        });

        route("GET", "/api/v1/query/{name}", request -> {
            String name = request.query().get("__name");
            Map<String, String> params = new LinkedHashMap<>(request.query());
            params.remove("token");
            params.remove("__name");
            return Response.json(200, call(() -> api.query(name, params)));
        });
    }

    /** The image type a frame is encoded as; the quality decides, because 100 means PNG. */
    private static String viewportContentType(int quality) {
        return quality >= 100 ? "image/png" : "image/jpeg";
    }

    /** The answer when this client cannot produce frames. */
    private Response viewportUnavailable() {
        return Response.json(503, error(
            "Live view is not available. The client must be in a world with a visible window."));
    }

    /** The reason remote control cannot run, or null when it can. */
    private String controlUnavailable() {
        if (controlTarget == null) return "Remote control is not available on this client.";
        if (!controlTarget.enabled()) return "Remote control is switched off on this client.";
        return null;
    }

    private Response controlRefused(String reason) {
        return Response.json(403, error(reason));
    }

    /** The answer when the map service was never started, rather than a stack trace. */
    private Response mapUnavailable() {
        return Response.json(503, error("The map service is not running on this client."));
    }

    /** Parses an optional integer query value; null when absent or not a number. */
    private static Integer intParam(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void route(String method, String pattern, Handler handler) {
        // The root route is "" after normalisation, so an empty pattern is a single empty segment.
        String trimmed = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        String[] raw = trimmed.isEmpty() ? new String[]{""} : trimmed.split("/", -1);
        String[] segments = new String[raw.length];
        boolean[] wildcards = new boolean[raw.length];
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].startsWith("{") && raw[i].endsWith("}")) {
                segments[i] = raw[i].substring(1, raw[i].length() - 1);
                wildcards[i] = true;
            } else {
                segments[i] = raw[i];
                wildcards[i] = false;
            }
        }
        routes.add(new Route(method, segments, wildcards, handler));
    }

    /** Splits a normalised path into segments; the empty path is a single empty segment. */
    private static String[] segmentsOf(String path) {
        return path.isEmpty() ? new String[]{""} : path.split("/", -1);
    }

    // ------------------------------------------------------------------ dispatch

    /** Handles a request end to end: rate limit, authenticate, route, and never throw. */
    public Response handle(Request request) {
        if (request == null) return Response.json(400, error("Malformed request."));
        if (!rateLimiter.allow(request.remoteAddress())) {
            return Response.json(429, error("Too many requests. Slow down."));
        }

        String path = normalizePath(request.path());

        // The app shell and the health probe are reachable without a token so a browser can load the
        // UI; the UI then sends the token on every data request. Everything under /api requires it.
        if (!isPublicPath(path) && !auth.isAuthorized(request.authorization(), request.queryToken())) {
            return Response.json(401, error("Not authorised. Check the access token."));
        }

        RouteMatch match = match(request.method(), path);
        if (match == null) {
            // A known path with the wrong method is a 405; an unknown path is a 404.
            if (matchAnyMethod(path) != null) {
                return Response.json(405, error("That method is not allowed here."));
            }
            return Response.json(404, error("No such endpoint."));
        }

        Request enriched = withPathParams(request, path, match);
        try {
            Response response = match.route().handler().handle(enriched);
            return response == null ? Response.noContent() : response;
        } catch (CompanionClientThread.CompanionTimeoutException e) {
            return Response.json(504, error("The game did not respond in time."));
        } catch (RuntimeException e) {
            // The detail belongs in the log, never in the response: an internal exception can carry a
            // file path, a stack frame or a secret, and the app only needs to know that it failed.
            lastError = e;
            return Response.json(500, error("The request failed. See the addon log for details."));
        } catch (Exception e) {
            lastError = e;
            return Response.json(500, error("The request failed. See the addon log for details."));
        }
    }

    /** The last internal failure, for the Minecraft layer to log. Never sent to a client. */
    private volatile Throwable lastError;

    public Throwable lastError() {
        return lastError;
    }

    private record RouteMatch(Route route, String[] pathSegments) {
    }

    private RouteMatch match(String method, String path) {
        String[] parts = segmentsOf(path);
        for (Route route : routes) {
            if (!route.method().equalsIgnoreCase(method)) continue;
            if (route.segments().length != parts.length) continue;
            boolean ok = true;
            for (int i = 0; i < parts.length; i++) {
                if (!route.wildcards()[i] && !route.segments()[i].equals(parts[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return new RouteMatch(route, parts);
        }
        return null;
    }

    private Route matchAnyMethod(String path) {
        String[] parts = segmentsOf(path);
        for (Route route : routes) {
            if (route.segments().length != parts.length) continue;
            boolean ok = true;
            for (int i = 0; i < parts.length; i++) {
                if (!route.wildcards()[i] && !route.segments()[i].equals(parts[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return route;
        }
        return null;
    }

    private Request withPathParams(Request request, String path, RouteMatch match) {
        String[] parts = segmentsOf(path);
        Map<String, String> query = new LinkedHashMap<>(request.query());
        for (int i = 0; i < parts.length; i++) {
            if (match.route().wildcards()[i]) {
                query.put("__" + match.route().segments()[i], decode(parts[i]));
            }
        }
        return new Request(request.method(), path, query, request.authorization(), request.body(),
            request.remoteAddress());
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a call on the client thread with the standard timeout. */
    private <T> T call(java.util.concurrent.Callable<T> task) {
        return clientThread.invoke(task, 5000);
    }

    private Response action(java.util.concurrent.Callable<CompanionApi.ActionResult> action) {
        return Response.json(200, CompanionJson.result(call(action)));
    }

    private Map<String, Object> health() {
        Map<String, Object> body = CompanionJson.map();
        body.put("ok", true);
        body.put("api", CompanionSnapshot.API_VERSION);
        body.put("node", config.nodeName());
        body.put("loopbackOnly", config.isLoopbackOnly());
        body.put("subscribers", events.subscriberCount());
        body.put("lastEventId", events.lastEventId());
        return body;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> body = CompanionJson.map();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }

    /** The path with any leading slash and query removed. */
    public static String normalizePath(String raw) {
        if (raw == null) return "";
        String path = raw;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return path;
    }

    /** Parses a URI's query string into a map. Repeated keys keep the last value. */
    public static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri == null ? null : uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return value;
        }
    }

    /** The content type used for the SSE stream. */
    public static String eventStreamContentType() {
        return "text/event-stream; charset=utf-8";
    }

    private Response eventsResponse(Request request) {
        // The stream itself is produced by CompanionServer, which owns the socket; the router only
        // reports that the endpoint exists and is authenticated.
        return new Response(200, eventStreamContentType(), "");
    }

    /** True when the router has a handler for this method and path, ignoring auth. */
    public boolean hasRoute(String method, String path) {
        return match(method, normalizePath(path)) != null;
    }

    /** Route method names, for the app's capability discovery. */
    public List<String> routePaths() {
        List<String> out = new ArrayList<>();
        for (Route route : routes) {
            out.add(route.method() + " /" + String.join("/", route.segments()));
        }
        out.sort(String::compareTo);
        return out;
    }
}
