package com.logvex.addon.companion.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Settings for the companion HTTP server.
 *
 * <p>Defaults are deliberately conservative: the server binds to loopback only, refuses to expose
 * itself without a token, and caps the request body. Exposing a client to a network is a decision the
 * user has to make explicitly, so the safe value is what happens when they do nothing.
 *
 * <p>The token is a secret. It is never logged, never included in a snapshot and never echoed in an
 * error message; {@link #toString()} redacts it so an accidental log line cannot leak it.
 */
public final class CompanionConfig {
    /** The largest request body accepted, in bytes. Keeps a hostile client from exhausting memory. */
    public static final int DEFAULT_MAX_BODY = 256 * 1024;

    private boolean enabled;
    private int port = 6767;
    private String bindAddress = "127.0.0.1";
    private String token = "";
    private boolean allowRemote;
    private int maxBodyBytes = DEFAULT_MAX_BODY;
    private int requestsPerMinute = 240;
    private int eventBufferSize = 200;
    private String nodeName = "Log Client";

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int port() {
        return port;
    }

    /** Clamps to the valid TCP range so an invalid stored value can never stop the server starting. */
    public void setPort(int port) {
        this.port = Math.max(1, Math.min(65535, port));
    }

    public String bindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress.trim();
    }

    /** The shared secret required on every request. Generated on demand when one is first needed. */
    public String token() {
        if (token == null || token.isBlank()) token = generateToken();
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token.trim();
    }

    /** True when a token is already configured, without generating one as a side effect. */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public boolean allowRemote() {
        return allowRemote;
    }

    public void setAllowRemote(boolean allowRemote) {
        this.allowRemote = allowRemote;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = Math.max(1024, Math.min(8 * 1024 * 1024, maxBodyBytes));
    }

    public int requestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = Math.max(10, Math.min(6000, requestsPerMinute));
    }

    public int eventBufferSize() {
        return eventBufferSize;
    }

    public void setEventBufferSize(int eventBufferSize) {
        this.eventBufferSize = Math.max(10, Math.min(2000, eventBufferSize));
    }

    public String nodeName() {
        return nodeName == null || nodeName.isBlank() ? "Log Client" : nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName == null ? "" : nodeName.trim();
    }

    /**
     * The address to bind, resolved. A remote bind is refused unless the user opted in, so a stray
     * config edit cannot silently expose the client to the network.
     */
    public InetAddress resolveBindAddress() throws UnknownHostException {
        String address = allowRemote ? bindAddress : "127.0.0.1";
        return InetAddress.getByName(address);
    }

    /** True when the configured bind is loopback-only, so the GUI can say so plainly. */
    public boolean isLoopbackOnly() {
        return !allowRemote;
    }

    /** A fresh 32-byte URL-safe token. */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Redacted so a config dump or a log line can never leak the secret. */
    @Override
    public String toString() {
        return "CompanionConfig{enabled=" + enabled + ", port=" + port + ", bind=" + bindAddress
            + ", remote=" + allowRemote + ", token=" + (hasToken() ? "<set>" : "<unset>") + "}";
    }
}
