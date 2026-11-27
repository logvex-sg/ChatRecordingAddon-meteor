package com.logvex.addon.companion.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The companion HTTP server: a thin transport over {@link CompanionRouter}.
 *
 * <p>It binds a loopback socket by default, serves the bundled app shell and the JSON API, and holds
 * open a Server-Sent Events stream per connected app so the UI updates live. All game work is
 * marshalled onto the client thread by the router, so this class only ever deals with bytes.
 *
 * <p>Every worker is a daemon and the whole server is shut down through {@link #stop()}, which
 * unblocks any open stream, so reloading a config or disabling the module cannot leak a thread or a
 * port. The bound port is discoverable through {@link #port()} because a port of 0 asks the OS for a
 * free one, which is what the tests use.
 */
public final class CompanionServer {
    private final CompanionConfig config;
    private final CompanionRouter router;
    private final CompanionEventBus events;
    private final String appHtml;
    private final CompanionMapSource mapSource;
    private final CompanionIconSource iconSource;

    /**
     * How many ports above the configured one to try. Sixteen is enough for a normal handful of
     * clients on one machine while still failing quickly if something is badly wrong.
     */
    private static final int PORT_SEARCH_RANGE = 16;

    private HttpServer server;
    private ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** A server with no map service; the map endpoints answer "not available". */
    public CompanionServer(
        CompanionConfig config,
        CompanionApi api,
        CompanionClientThread clientThread,
        CompanionEventBus events,
        String appHtml
    ) {
        this(config, api, clientThread, events, appHtml, null);
    }

    public CompanionServer(
        CompanionConfig config,
        CompanionApi api,
        CompanionClientThread clientThread,
        CompanionEventBus events,
        String appHtml,
        CompanionMapSource mapSource
    ) {
        this(config, api, clientThread, events, appHtml, mapSource, null);
    }

    /** The full constructor, with an icon source for the inventory. */
    public CompanionServer(
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
     * <p>Either of the last two may be null, and the app then reports the feature as unavailable
     * instead of failing: a client with no renderer still has a working dashboard.
     */
    public CompanionServer(
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
        this.events = events;
        this.appHtml = appHtml;
        this.mapSource = mapSource;
        this.iconSource = iconSource;
        this.router = new CompanionRouter(
            config, api, clientThread, events, appHtml, mapSource, iconSource,
            viewportSource, controlTarget);
    }

    public CompanionRouter router() {
        return router;
    }

    public CompanionEventBus events() {
        return events;
    }

    public boolean isRunning() {
        return running.get() && server != null;
    }

    /** The bound port, or -1 when the server is not running. */
    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /**
     * Starts the server, moving to the next free port when the configured one is taken.
     *
     * <p>Two Minecraft instances on one machine share the addon data folder and therefore the same
     * default port, so the second one to launch used to fail to bind and simply had no companion.
     * A taken port is an ordinary condition here, not an error: the server walks forward to the
     * first port it can have and reports it through {@link #port()}. The configured port is never
     * modified, so the preference survives a restart.
     *
     * @throws IOException when no port in the search range can be bound
     */
    public synchronized void start() throws IOException {
        if (isRunning()) return;
        int preferred = config.port();
        IOException last = null;
        for (int offset = 0; offset < PORT_SEARCH_RANGE; offset++) {
            int candidate = preferred + offset;
            if (candidate > 65535) break;
            try {
                server = HttpServer.create(
                    new InetSocketAddress(config.resolveBindAddress(), candidate), 32);
                last = null;
                break;
            } catch (IOException e) {
                // A port already in use is expected when a second client starts; keep looking.
                last = e;
            }
        }
        if (server == null) {
            throw last == null ? new IOException("No free port near " + preferred) : last;
        }
        workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "logvex-companion-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", this::dispatch);
        server.start();
        running.set(true);
    }

    /** Stops the server and releases the port. Idempotent. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        running.set(false);
    }

    // ------------------------------------------------------------------ transport

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            String path = CompanionRouter.normalizePath(uri.getPath());

            if ("GET".equalsIgnoreCase(method) && path.equals("api/v1/events")) {
                // The stream is held open here rather than by the router, so it has to apply the
                // router's rules itself: without this it was served to any client that could reach
                // the port, and with allowRemote that is the whole network.
                CompanionRouter.Request request = new CompanionRouter.Request(
                    method,
                    path,
                    CompanionRouter.parseQuery(uri),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    "",
                    remoteAddress(exchange));
                if (!router.rateLimiter().allow(request.remoteAddress())) {
                    write(exchange, router.errorResponse(429, "Too many requests. Slow down."));
                    return;
                }
                if (!router.isAuthorized(request)) {
                    write(exchange, router.errorResponse(401, "Not authorised. Check the access token."));
                    return;
                }
                streamEvents(exchange);
                return;
            }

            String body = readBody(exchange);
            CompanionRouter.Request request = new CompanionRouter.Request(
                method,
                path,
                CompanionRouter.parseQuery(uri),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body,
                remoteAddress(exchange));
            CompanionRouter.Response response = router.handle(request);
            write(exchange, response);
        } catch (IOException e) {
            closeQuietly(exchange);
        } catch (RuntimeException e) {
            // A transport-level failure must not kill the worker thread or the server.
            closeQuietly(exchange);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        int limit = config.maxBodyBytes();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("Request body too large.");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private void write(HttpExchange exchange, CompanionRouter.Response response) throws IOException {
        byte[] bytes = response.binary() != null ? response.binary()
            : response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // The token travels in the query string, so it would leak to any third-party resource the
        // app loads through the Referer header. The app needs no referrer at all.
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        // The app can send chat and run commands, so a page that frames it can make the operator
        // click through to sending something. Refuse to be framed.
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    /**
     * Holds the connection open and writes each event as it arrives.
     *
     * <p>The subscriber never blocks the publisher: it drops the event into a bounded queue and this
     * loop drains it. When the queue is full the client is disconnected instead of being allowed to
     * slow the game down, and the app reconnects and replays from its last event id.
     */
    private void streamEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", CompanionRouter.eventStreamContentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(200, 0);

        BlockingQueue<String> outbox = new ArrayBlockingQueue<>(256);
        AtomicBoolean open = new AtomicBoolean(true);

        // Replay what the client missed, so a reconnect is seamless.
        long lastSeen = lastEventId(exchange);
        for (CompanionEventBus.Event event : events.recent(200)) {
            if (event.id() <= lastSeen) continue;
            if (!outbox.offer(frame(event))) break;
        }

        CompanionEventBus.Subscriber subscriber = event -> {
            if (!open.get()) return false;
            boolean accepted = outbox.offer(frame(event));
            if (!accepted) {
                open.set(false);
                return false;
            }
            return true;
        };
        events.subscribe(subscriber, false);

        OutputStream out = exchange.getResponseBody();
        try {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (open.get()) {
                String frame = outbox.poll(15, TimeUnit.SECONDS);
                if (frame == null) {
                    // A comment keeps the connection alive through proxies that time out idle streams.
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            open.set(false);
            events.unsubscribe(subscriber);
            closeQuietly(exchange);
        }
    }

    private static String frame(CompanionEventBus.Event event) {
        return "id: " + event.id() + "\n"
            + "event: " + event.type() + "\n"
            + "data: " + event.json() + "\n\n";
    }

    private static long lastEventId(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Last-Event-ID");
        if (values == null || values.isEmpty()) return 0L;
        try {
            return Long.parseLong(values.get(0).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String remoteAddress(HttpExchange exchange) {
        var address = exchange.getRemoteAddress();
        return address == null || address.getAddress() == null
            ? "unknown"
            : address.getAddress().getHostAddress();
    }

    private static void closeQuietly(HttpExchange exchange) {
        try {
            exchange.close();
        } catch (RuntimeException ignored) {
            // Nothing useful to do if the socket is already gone.
        }
    }

    /** The app HTML served at the root. Exposed so a test can assert it is non-trivial. */
    public String appHtml() {
        return appHtml;
    }
}
