package com.logvex.addon.companion.core;

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

    /** A response: a status, a content type and a body. */
    public record Response(int status, String contentType, String body) {
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
    private final List<Route> routes = new ArrayList<>();

    public CompanionRouter(
        CompanionConfig config,
        CompanionApi api,
        CompanionClientThread clientThread,
        CompanionEventBus events,
        String appHtml
    ) {
        this.config = config;
        this.api = api;
        this.clientThread = clientThread;
        this.events = events;
        this.appHtml = appHtml;
        this.auth = new CompanionAuth(config);
        this.rateLimiter = new CompanionRateLimiter(config.requestsPerMinute());
        registerRoutes();
    }

    public CompanionRateLimiter rateLimiter() {
        return rateLimiter;
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
        route("GET", "/api/v1/storage", request -> Response.json(200, call(api::snapshot).storage()));
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

        route("GET", "/api/v1/query/{name}", request -> {
            String name = request.query().get("__name");
            Map<String, String> params = new LinkedHashMap<>(request.query());
            params.remove("token");
            params.remove("__name");
            return Response.json(200, call(() -> api.query(name, params)));
        });
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
        boolean isAppShell = path.isEmpty() || path.equals("index.html") || path.equals("health");

        // The app shell and the health probe are reachable without a token so a browser can load the
        // UI; the UI then sends the token on every data request. Everything under /api requires it.
        if (!isAppShell && !auth.isAuthorized(request.authorization(), request.queryToken())) {
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
