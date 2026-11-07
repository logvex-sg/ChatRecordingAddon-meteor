package com.logvex.addon.companion.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Token authentication for the companion server.
 *
 * <p>Every request must carry the shared token, either as {@code Authorization: Bearer <token>} or as
 * a {@code token} query parameter (the query form exists so a browser can open the app with a link,
 * and so an {@code EventSource} can connect, which cannot set headers). The comparison is
 * constant-time, so a wrong token cannot be recovered one byte at a time by timing the response.
 *
 * <p>The token is never logged and never returned by any method here.
 */
public final class CompanionAuth {
    private final CompanionConfig config;

    public CompanionAuth(CompanionConfig config) {
        this.config = config;
    }

    /**
     * Checks a request's credentials.
     *
     * @param authorization the {@code Authorization} header, or null
     * @param queryToken    the {@code token} query parameter, or null
     * @return true when the credentials match the configured token
     */
    public boolean isAuthorized(String authorization, String queryToken) {
        String expected = config.token();
        String presented = extract(authorization, queryToken);
        if (presented == null || presented.isEmpty()) return false;
        return constantTimeEquals(expected, presented);
    }

    private static String extract(String authorization, String queryToken) {
        if (queryToken != null && !queryToken.isEmpty()) return queryToken;
        if (authorization == null) return null;
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }

    /** Length-independent, byte-by-byte comparison using the message digest of each input. */
    private static boolean constantTimeEquals(String expected, String presented) {
        byte[] a = digest(expected);
        byte[] b = digest(presented);
        return MessageDigest.isEqual(a, b);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // SHA-256 is always present; this cannot happen on a conforming JVM.
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** The header name the app may use instead of a query parameter. */
    public static String headerName() {
        return "Authorization";
    }
}
