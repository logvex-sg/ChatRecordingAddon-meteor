package com.logvex.addon.update.core;

/**
 * Compares semantic version strings such as {@code 13.0.0} or {@code 13.2.0-beta.1}.
 *
 * <p>The comparison is deliberately forgiving: a leading {@code v} is ignored, a missing or
 * non-numeric component counts as zero, and a pre-release suffix ranks below the matching release
 * ({@code 1.0.0-beta} &lt; {@code 1.0.0}). Anything that cannot be read as a version at all is
 * treated as {@code 0}, so a malformed tag can never be reported as newer than the installed build.
 *
 * <p>This class is Minecraft-free on purpose: the version rules are the part most likely to be
 * wrong, so they are unit-tested directly.
 */
public final class Version implements Comparable<Version> {
    private final int major;
    private final int minor;
    private final int patch;
    /** The pre-release tag without its leading hyphen, or empty for a release. */
    private final String preRelease;
    private final String original;

    private Version(int major, int minor, int patch, String preRelease, String original) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
        this.original = original;
    }

    /** Parses {@code text}; never returns null, using {@code 0.0.0} when nothing parses. */
    public static Version parse(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) return new Version(0, 0, 0, "", raw);

        // A tag may be "v13.0.0", "release-13.0.0" or "13.0.0-beta.1"; take the first numeric run.
        int start = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isDigit(raw.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) return new Version(0, 0, 0, "", raw);

        String body = raw.substring(start);
        // The version is the leading run of digits and dots. A pre-release marker counts only when
        // it directly follows that run and is one unbroken token.
        //
        // Scanning for a hyphen anywhere was wrong: release titles are prose, and
        // "Log Client 18.27.0 — auto-login answers the prompt" put the whole headline into the
        // version as a pre-release tag, which then reached the player as
        // "18.27.0-login answers the prompt, and ai reactions explains itself".
        int bodyEnd = 0;
        while (bodyEnd < body.length()) {
            char c = body.charAt(bodyEnd);
            if (!Character.isDigit(c) && c != '.') break;
            bodyEnd++;
        }
        String pre = "";
        // Only a hyphen introduces a pre-release. A '+' introduces build metadata, which semver
        // ignores for ordering, so "2.3.4+build.7" is version 2.3.4.
        if (bodyEnd < body.length() && body.charAt(bodyEnd) == '-') {
            int preEnd = bodyEnd + 1;
            while (preEnd < body.length() && !Character.isWhitespace(body.charAt(preEnd))) preEnd++;
            pre = body.substring(bodyEnd + 1, preEnd);
        }
        body = body.substring(0, bodyEnd);

        String[] parts = body.split("\\.");
        int major = component(parts, 0);
        int minor = component(parts, 1);
        int patch = component(parts, 2);
        return new Version(major, minor, patch, pre, raw);
    }

    private static int component(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String part = parts[index].trim();
        if (part.isEmpty()) return 0;
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            // "13.x.0" and similar: read the leading digits, otherwise treat as zero.
            int end = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
            if (end == 0) return 0;
            try {
                return Integer.parseInt(part.substring(0, end));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    public String preRelease() {
        return preRelease;
    }

    public String original() {
        return original;
    }

    @Override
    public int compareTo(Version other) {
        if (other == null) return 1;
        int result = Integer.compare(major, other.major);
        if (result != 0) return result;
        result = Integer.compare(minor, other.minor);
        if (result != 0) return result;
        result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        // A release outranks its own pre-releases.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0;
        if (preRelease.isEmpty()) return 1;
        if (other.preRelease.isEmpty()) return -1;
        return preRelease.compareToIgnoreCase(other.preRelease);
    }

    /** True when this version is strictly newer than {@code other}. */
    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch;
        return preRelease.isEmpty() ? base : base + "-" + preRelease;
    }
}
