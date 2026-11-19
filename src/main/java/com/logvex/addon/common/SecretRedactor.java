package com.logvex.addon.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replaces known secrets in text before it is stored, exported or forwarded.
 *
 * <p>This addon records chat and can relay it to a Discord webhook. A password the client itself
 * sent, or a server that echoes one back, would otherwise be archived and relayed. Registration is
 * opt-in: {@code AutoLoginModule} registers each stored password, and every recorded line is then
 * scrubbed before it reaches the live buffer, the archive or a webhook.
 *
 * <p>Only exact, known values are replaced. This is not a heuristic scanner for "password-like"
 * text: guessing would corrupt ordinary chat and still miss the real value.
 *
 * <p>Minecraft-free so the replacement rules are unit tested.
 */
public final class SecretRedactor {
    /** What a redacted value is replaced with. */
    public static final String PLACEHOLDER = "[redacted]";

    /** Shortest value worth registering; redacting a 1-character secret would mangle all chat. */
    private static final int MIN_LENGTH = 3;

    private static final List<String> SECRETS = new CopyOnWriteArrayList<>();

    private SecretRedactor() {
    }

    /** Registers a value to redact from now on. Shorter values are ignored. */
    public static void register(String secret) {
        if (secret == null || secret.length() < MIN_LENGTH) return;
        if (!SECRETS.contains(secret)) SECRETS.add(secret);
    }

    /** Forgets every registered value. */
    public static void clear() {
        SECRETS.clear();
    }

    /** Replaces every registered secret in {@code text}; returns the input when there is nothing to do. */
    public static String redact(String text) {
        if (text == null || text.isEmpty() || SECRETS.isEmpty()) return text;
        String out = text;
        // Longest first: if one secret contains another, the longer one must be replaced whole.
        List<String> ordered = new ArrayList<>(SECRETS);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : ordered) {
            if (out.contains(secret)) out = out.replace(secret, PLACEHOLDER);
        }
        return out;
    }

    /** True when {@code text} contains any registered secret. */
    public static boolean containsSecret(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String secret : SECRETS) {
            if (text.contains(secret)) return true;
        }
        return false;
    }

    /** How many values are registered, for diagnostics. Never the values. */
    public static int size() {
        return SECRETS.size();
    }
}
