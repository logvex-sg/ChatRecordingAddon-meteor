package com.logvex.addon.companion;

import com.logvex.addon.companion.core.CompanionClientThread;
import com.logvex.addon.companion.core.CompanionConfig;
import com.logvex.addon.companion.core.CompanionEventBus;
import com.logvex.addon.companion.core.CompanionServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live event stream must be as protected as the JSON API beside it.
 *
 * <p>The stream carried chat lines, commands and server details to anyone who could open the socket.
 * The token was only checked by the router, and the stream is served before the router sees the
 * request, so a request without a token was answered with the feed. With {@code allowRemote} the
 * server binds every interface, which made that a leak to the whole network rather than to the
 * machine.
 *
 * <p>These tests drive the real socket rather than the router, because the router was already
 * correct: the bug was that the stream never reached it.
 */
class CompanionStreamAuthTest {

    private static final String TOKEN = "stream-auth-token-0123456789";

    private CompanionServer server;
    private int port;

    private void start() throws Exception {
        CompanionConfig config = new CompanionConfig();
        config.setToken(TOKEN);
        config.setPort(0);
        server = new CompanionServer(config, new FakeCompanionApi(), CompanionClientThread.direct(),
            new CompanionEventBus(20), "<html><body>app</body></html>");
        server.start();
        port = server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private int statusOf(String query) throws Exception {
        return responseOf(query).status;
    }

    /** A status and body pair from one request. */
    private record Reply(int status, String body) {
    }

    /** Opens the stream and returns the status and the body. */
    private Reply responseOf(String query) throws Exception {
        URL url = URI.create("http://127.0.0.1:" + port + "/api/v1/events" + query).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try {
            int status = connection.getResponseCode();
            if (status == 200) {
                // Drain a token amount so the handler is not blocked on a full buffer.
                try (InputStream in = connection.getInputStream()) {
                    in.read(new byte[16]);
                }
                return new Reply(status, "");
            }
            String body;
            try (InputStream in = connection.getErrorStream()) {
                body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return new Reply(status, body);
        } finally {
            connection.disconnect();
        }
    }

    @Test
    @DisplayName("the event stream refuses a request with no token")
    void streamRequiresToken() throws Exception {
        start();
        int status = statusOf("");
        assertNotEquals(200, status,
            "the stream served chat events without a token, so anyone reaching the port could read"
                + " the log");
        assertEquals(401, status, "an unauthenticated stream must be refused the same way as the API");
    }

    @Test
    @DisplayName("the event stream refuses a wrong token")
    void streamRejectsWrongToken() throws Exception {
        start();
        assertEquals(401, statusOf("?token=not-the-real-token"),
            "a wrong token must not be accepted just because one was supplied");
    }

    @Test
    @DisplayName("the event stream still accepts the real token")
    void streamAcceptsRealToken() throws Exception {
        start();
        assertEquals(200, statusOf("?token=" + TOKEN),
            "the app sends the token in the query string, so that path must keep working");
    }

    @Test
    @DisplayName("a refused stream answers with the same error body as the API")
    void refusedStreamUsesTheApiErrorShape() throws Exception {
        start();
        Reply reply = responseOf("");
        assertEquals(401, reply.status());
        // The dashboard reads "message" off a failed request. A bare JSON string has no such field,
        // so the refusal would surface as a parse error instead of the reason.
        assertTrue(reply.body().contains("\"ok\":false"),
            "the stream's refusal must carry the ok flag the rest of the API sends: " + reply.body());
        assertTrue(reply.body().contains("\"message\""),
            "the stream's refusal must carry a message the dashboard can show: " + reply.body());
    }

    @Test
    @DisplayName("the health probe stays open so the app can find a running client")
    void healthStaysOpen() throws Exception {
        start();
        URL url = URI.create("http://127.0.0.1:" + port + "/health").toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try {
            assertEquals(200, connection.getResponseCode(),
                "the shell and health probe are how a browser loads the app before it has a token");
        } finally {
            connection.disconnect();
        }
    }
}
