package com.logvex.addon.update;

import com.logvex.addon.update.core.ReleaseAsset;
import com.logvex.addon.update.core.ReleaseInfo;
import com.logvex.addon.update.core.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape of a real release, checked through the same code the updater uses.
 *
 * <p>Releases here are tagged with a bare counter ({@code v36}) while the version lives in the
 * title. Both the version and the jar choice have been wrong before, in ways that only showed
 * against a live release, so this pins the payload this release actually produced.
 */
class LiveReleaseCheckTest {

    private static ReleaseInfo release() {
        // Mirrors the v36 payload: bare tag, version in the title, sources jar first (GitHub sorts
        // assets alphabetically, so the sources jar really does come first).
        return new ReleaseInfo(
            "v36",
            "Log Client 18.18.0 — record out of the box, and route keyword alerts",
            "notes",
            false,
            "https://github.com/logvex-sg/ChatRecordingAddon-meteor/releases/tag/v36",
            "2026-11-18T00:00:00Z",
            List.of(
                new ReleaseAsset("log-client-18.18.0-sources.jar",
                    "https://example/sources", 963138, ""),
                new ReleaseAsset("log-client-18.18.0.jar",
                    "https://example/mod", 1844670, "")));
    }

    @Test
    void theVersionComesFromTheTitleNotTheBareTag() {
        assertEquals(0, release().version().compareTo(Version.parse("18.18.0")),
            "a bare v36 tag must not be read as version 36");
    }

    @Test
    void itIsNewerThanThePreviousRelease() {
        assertTrue(release().isNewerThan(Version.parse("18.17.1")));
        assertFalse(release().isNewerThan(Version.parse("18.18.0")),
            "the same version must not be offered as an update");
    }

    @Test
    void theModJarIsChosenOverTheSourcesJar() {
        ReleaseAsset jar = release().primaryJar();
        assertNotNull(jar);
        assertEquals("log-client-18.18.0.jar", jar.name(),
            "installing the sources jar would replace the mod with source code");
    }
}
