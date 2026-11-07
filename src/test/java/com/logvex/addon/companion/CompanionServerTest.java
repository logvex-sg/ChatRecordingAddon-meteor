package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The companion server over a real socket: binding, HTTP, authentication and the live event stream.
 *
 * <p>It binds port 0, so the OS picks a free port and the tests never collide with a running client
 * or with each other.
 */
class CompanionServerTest {
    private static final String TOKEN = "socket-test-token-0123456789";

    private CompanionServer server;

    private CompanionServer start(FakeCompanionApi api, CompanionEventBus events) throws Exception {
        CompanionConfig config = new CompanionConfig();
        config.setToken(TOKEN);
        config.setPort(0);
        server = new CompanionServer(config, api, CompanionClientThread.direct(), events,
            "<html><body>Log Addon Companion</body></html>");
        server.start();
        return server;
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private static HttpURLConnection open(String path, String method, String body, boolean withToken)
        throws Exception {
        URL url = URI.create("http://127.0.0.1:" + PORT + path).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        if (withToken) connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private static int PORT;

    private static String read(HttpURLConnection connection) throws Exception {
        try (InputStream in = connection.getResponseCode() >= 400
            ? connection.getErrorStream()
            : connection.getInputStream()) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the server binds a real port, serves the app and the API, and stops cleanly")
    void servesOverSocket() throws Exception {
        start(new FakeCompanionApi(), new CompanionEventBus(20));
        PORT = server.port();
        assertTrue(PORT > 0, "the server must report the bound port");

        HttpURLConnection app = open("/", "GET", null, false);
        assertEquals(200, app.getResponseCode());
        assertTrue(read(app).contains("Log Addon Companion"));

        HttpURLConnection unauth = open("/api/v1/snapshot", "GET", null, false);
        assertEquals(401, unauth.getResponseCode());
        read(unauth);

        HttpURLConnection auth = open("/api/v1/snapshot", "GET", null, true);
        assertEquals(200, auth.getResponseCode());
        assertTrue(read(auth).contains("node-1"));

        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    @DisplayName("a POST body reaches the API and the response is JSON")
    void postBody() throws Exception {
        FakeCompanionApi api = new FakeCompanionApi();
        start(api, new CompanionEventBus(20));
        PORT = server.port();

        HttpURLConnection connection = open("/api/v1/chat", "POST", "{\"message\":\"hi there\"}", true);
        assertEquals(200, connection.getResponseCode());
        assertTrue(read(connection).contains("Sent"));
        assertEquals("hi there", api.lastChat);
    }

    @Test
    @DisplayName("the SSE stream delivers a published event to a connected app")
    void eventStream() throws Exception {
        CompanionEventBus events = new CompanionEventBus(50);
        start(new FakeCompanionApi(), events);
        PORT = server.port();

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        List<String> lines = new ArrayList<>();

        Thread reader = new Thread(() -> {
            try {
                URL url = URI.create("http://127.0.0.1:" + PORT + "/api/v1/events?token=" + TOKEN).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setReadTimeout(8000);
                try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    connected.countDown();
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (lines) {
                            lines.add(line);
                        }
                        if (line.startsWith("data:")) {
                            received.set(line);
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {
                // The test asserts on what arrived; a closed stream at teardown is expected.
            }
        }, "sse-reader");
        reader.setDaemon(true);
        reader.start();

        assertTrue(connected.await(5, TimeUnit.SECONDS), "the stream must connect");
        // Wait for the subscriber to register, then publish.
        for (int i = 0; i < 50 && events.subscriberCount() == 0; i++) Thread.sleep(20);
        assertTrue(events.subscriberCount() > 0, "the stream must register a subscriber");
        events.publish("chat", "{\"message\":\"live update\"}");

        reader.join(5000);
        assertNotNull(received.get(), "the event must reach the stream");
        assertTrue(received.get().contains("live update"));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("event: chat")));
    }

    @Test
    @DisplayName("the SSE stream replays events the client missed on reconnect")
    void streamReplaysBacklog() throws Exception {
        CompanionEventBus events = new CompanionEventBus(50);
        events.publish("chat", "{\"message\":\"first\"}");
        events.publish("chat", "{\"message\":\"second\"}");
        start(new FakeCompanionApi(), events);
        PORT = server.port();

        AtomicReference<String> firstData = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                URL url = URI.create("http://127.0.0.1:" + PORT + "/api/v1/events?token=" + TOKEN).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setReadTimeout(5000);
                try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            firstData.set(line);
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "sse-backlog");
        reader.setDaemon(true);
        reader.start();
        reader.join(5000);

        assertNotNull(firstData.get(), "the backlog must be replayed");
        assertTrue(firstData.get().contains("first"), "the oldest missed event comes first");
    }
}
