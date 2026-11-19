package com.logvex.addon.assistant.core;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who sent a message, as far as the client can actually prove it.
 *
 * <p>A chat line carries a name and nothing else. That name is <em>not</em> proof of identity: on an
 * offline-mode server any client can pick any name, and a name can be spoofed in a plugin-formatted
 * line. So this record keeps the two facts apart:
 *
 * <ul>
 *   <li>{@link #name()} — the name the line claimed. Always present, never trusted on its own.</li>
 *   <li>{@link #uuid()} — the account UUID, when the client's player list could resolve the name to
 *       one. Null when the client cannot prove who spoke.</li>
 * </ul>
 *
 * <p>A whitelist entry that pins a UUID is matched against {@link #uuid()} only, so a spoofed name
 * cannot impersonate a whitelisted account. A name-only entry falls back to the name, which is the
 * best that can be done on a server that does not publish UUIDs — {@link PermissionSet} can be told
 * to refuse that fallback entirely.
 */
public record SenderIdentity(String name, UUID uuid, boolean local) {
    /** A vanilla-compatible player name. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** A UUID with or without dashes. */
    private static final Pattern UUID_TEXT = Pattern.compile(
        "^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$");

    public SenderIdentity {
        if (name == null) name = "";
        name = name.trim();
    }

    /** The local player, whose identity the client knows exactly. */
    public static SenderIdentity local(String name, UUID uuid) {
        return new SenderIdentity(name, uuid, true);
    }

    /** A player whose account the client could not resolve; the name alone is unverified. */
    public static SenderIdentity unverified(String name) {
        return new SenderIdentity(name, null, false);
    }

    /** True when the client could pin this speaker to an account. */
    public boolean verified() {
        return uuid != null;
    }

    /** True when this identity can be trusted for a UUID-pinned permission entry. */
    public boolean nameIsValid() {
        return NAME.matcher(name).matches();
    }

    /** The UUID as text, or an empty string when unknown. */
    public String uuidText() {
        return uuid == null ? "" : uuid.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SenderIdentity that)) return false;
        return local == that.local && Objects.equals(name, that.name) && Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, uuid, local);
    }

    /** True when {@code text} is a legal Minecraft name. */
    public static boolean validName(String text) {
        return text != null && NAME.matcher(text.trim()).matches();
    }

    /** Parses a UUID, returning null when the text is not one. */
    public static UUID parseUuid(String text) {
        if (text == null || !UUID_TEXT.matcher(text.trim()).matches()) return null;
        try {
            String bare = text.trim().replace("-", "");
            long most = Long.parseUnsignedLong(bare.substring(0, 16), 16);
            long least = Long.parseUnsignedLong(bare.substring(16), 16);
            return new UUID(most, least);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
