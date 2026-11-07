package com.logvex.addon.update;

import com.logvex.addon.update.core.Checksums;
import com.logvex.addon.update.core.ReleaseAsset;
import com.logvex.addon.update.core.ReleaseInfo;
import com.logvex.addon.update.core.UpdateChannel;
import com.logvex.addon.update.core.UpdateEngine;
import com.logvex.addon.update.core.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure update rules: version parsing, channel selection and checksum validation. */
class VersionTest {

    @Test
    @DisplayName("parses plain, prefixed and pre-release tags")
    void parsesTags() {
        assertEquals("13.0.0", Version.parse("13.0.0").toString());
        assertEquals("13.0.0", Version.parse("v13.0.0").toString());
        assertEquals("13.2.1", Version.parse("release-13.2.1").toString());
        assertEquals("1.0.0-beta", Version.parse("1.0.0-beta").toString());
        assertEquals("2.3.4", Version.parse("2.3.4+build.7").toString());
    }

    @Test
    @DisplayName("a malformed tag never outranks a real version")
    void malformedIsZero() {
        assertEquals(0, Version.parse("not-a-version").compareTo(Version.parse("0.0.0")));
        assertFalse(Version.parse("garbage").isNewerThan(Version.parse("13.0.0")));
        assertFalse(Version.parse("").isNewerThan(Version.parse("0.0.0")));
    }

    @Test
    @DisplayName("components compare numerically, not lexically")
    void numericOrdering() {
        assertTrue(Version.parse("13.10.0").isNewerThan(Version.parse("13.9.0")));
        assertTrue(Version.parse("13.0.1").isNewerThan(Version.parse("13.0.0")));
        assertTrue(Version.parse("14.0.0").isNewerThan(Version.parse("13.99.99")));
    }

    @Test
    @DisplayName("a release outranks its own pre-releases")
    void releaseBeatsPreRelease() {
        assertTrue(Version.parse("1.0.0").isNewerThan(Version.parse("1.0.0-beta")));
        assertFalse(Version.parse("1.0.0-beta").isNewerThan(Version.parse("1.0.0")));
        assertTrue(Version.parse("1.0.1-beta").isNewerThan(Version.parse("1.0.0")));
    }

    @Test
    @DisplayName("channel selection honours the pre-release flag")
    void channelSelection() {
        ReleaseInfo stable = release("v13.1.0", false);
        ReleaseInfo beta = release("v13.2.0-beta", true);

        assertEquals("13.1.0", UpdateEngine
            .selectBest(List.of(beta, stable), UpdateChannel.STABLE).version().toString());
        assertEquals("13.2.0-beta", UpdateEngine
            .selectBest(List.of(beta, stable), UpdateChannel.BETA).version().toString());
    }

    @Test
    @DisplayName("the newest acceptable release wins regardless of list order")
    void picksNewest() {
        List<ReleaseInfo> releases = List.of(
            release("v12.0.0", false),
            release("v13.5.0", false),
            release("v13.2.0", false));
        assertEquals("13.5.0", UpdateEngine
            .selectBest(releases, UpdateChannel.STABLE).version().toString());
    }

    @Test
    @DisplayName("checksum validation accepts a correct digest and rejects a wrong one")
    void checksumValidation() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("logclient-checksum", ".bin");
        try {
            java.nio.file.Files.writeString(file, "hello");
            // The well-known SHA-256 of "hello".
            String expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
            assertTrue(Checksums.matches(file, expected));
            assertTrue(Checksums.matches(file, "sha256:" + expected));
            assertTrue(Checksums.matches(file, expected.toUpperCase(java.util.Locale.ROOT)));
            assertFalse(Checksums.matches(file, "0".repeat(64)));
            assertFalse(Checksums.matches(file, "not-a-checksum"));
            assertFalse(Checksums.matches(file, null));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static ReleaseInfo release(String tag, boolean preRelease) {
        return new ReleaseInfo(tag, tag, "", preRelease, "", "",
            List.of(new ReleaseAsset("log-client.jar", "https://example.invalid/a.jar", 1024, "")));
    }
}
