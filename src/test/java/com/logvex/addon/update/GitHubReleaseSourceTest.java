package com.logvex.addon.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.logvex.addon.update.core.GitHubReleaseSource;
import com.logvex.addon.update.core.Version;
import com.logvex.addon.update.core.ReleaseInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the GitHub Releases JSON, including the fields that are easy to get wrong. */
class GitHubReleaseSourceTest {

    private static final String JSON = """
        [
          {
            "tag_name": "v13.1.0",
            "name": "Log Client 13.1.0",
            "body": "## Changes\\n- Fixed a thing",
            "draft": false,
            "prerelease": false,
            "html_url": "https://github.com/logvex-sg/ChatRecordingAddon-meteor/releases/tag/v13.1.0",
            "published_at": "2026-11-01T10:00:00Z",
            "assets": [
              {"name": "log-client-13.1.0.jar", "browser_download_url": "https://x/a.jar", "size": 4096,
               "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"},
              {"name": "sources.zip", "browser_download_url": "https://x/s.zip", "size": 10}
            ]
          },
          {
            "tag_name": "v13.2.0-beta",
            "prerelease": true,
            "assets": []
          },
          {
            "tag_name": "v13.0.0",
            "draft": true,
            "prerelease": false,
            "assets": [{"name": "draft.jar", "browser_download_url": "https://x/d.jar"}]
          }
        ]
        """;

    private static JsonArray parse() {
        return JsonParser.parseString(JSON).getAsJsonArray();
    }

    @Test
    @DisplayName("drafts are always excluded")
    void excludesDrafts() {
        List<ReleaseInfo> stable = GitHubReleaseSource.parseReleases(parse(), false);
        assertTrue(stable.stream().noneMatch(r -> r.tagName().equals("v13.0.0")));
    }

    @Test
    @DisplayName("the stable channel excludes pre-releases; the beta channel includes them")
    void channelFiltering() {
        List<ReleaseInfo> stable = GitHubReleaseSource.parseReleases(parse(), false);
        List<ReleaseInfo> beta = GitHubReleaseSource.parseReleases(parse(), true);

        assertEquals(1, stable.size());
        assertEquals("v13.1.0", stable.get(0).tagName());
        assertEquals(2, beta.size());
        assertTrue(beta.stream().anyMatch(ReleaseInfo::preRelease));
    }

    @Test
    @DisplayName("assets and their checksums are read")
    void readsAssets() {
        ReleaseInfo release = GitHubReleaseSource.parseReleases(parse(), false).get(0);
        assertEquals(2, release.assets().size());
        assertEquals("log-client-13.1.0.jar", release.primaryJar().name());
        assertEquals(4096, release.primaryJar().size());
        assertTrue(release.primaryJar().hasChecksum());
    }

    @Test
    @DisplayName("missing fields degrade to sensible defaults instead of throwing")
    void tolerantOfMissingFields() {
        JsonArray minimal = JsonParser.parseString("""
            [{"tag_name": "v1.2.3"}]
            """).getAsJsonArray();
        List<ReleaseInfo> releases = GitHubReleaseSource.parseReleases(minimal, false);
        assertEquals(1, releases.size());
        ReleaseInfo release = releases.get(0);
        assertFalse(release.preRelease());
        assertEquals("v1.2.3", release.name());
        assertTrue(release.assets().isEmpty());
        assertEquals(null, release.primaryJar());
    }

    @Test
    @DisplayName("a release with no jar asset reports none, so download can refuse cleanly")
    void noJarAsset() {
        JsonArray array = JsonParser.parseString("""
            [{"tag_name": "v1.0.0", "assets": [{"name": "notes.txt", "browser_download_url": "https://x/n.txt"}]}]
            """).getAsJsonArray();
        assertTrue(GitHubReleaseSource.parseReleases(array, false).get(0).primaryJar() == null);
    }

    @Test
    @DisplayName("a sources jar is never chosen over the mod jar")
    void sourcesJarIsNotTheDownload() {
        // GitHub returns assets alphabetically and "-sources" sorts before ".jar", so taking the
        // first .jar hands the player source code instead of the mod.
        JsonArray array = JsonParser.parseString("""
            [{"tag_name": "v33", "name": "Log Client 18.16.0", "assets": [
                {"name": "log-client-18.16.0-sources.jar", "browser_download_url": "https://x/s.jar", "size": 940},
                {"name": "log-client-18.16.0.jar", "browser_download_url": "https://x/m.jar", "size": 1814}]}]
            """).getAsJsonArray();
        ReleaseInfo release = GitHubReleaseSource.parseReleases(array, false).get(0);
        assertEquals("log-client-18.16.0.jar", release.primaryJar().name());
        assertEquals("https://x/m.jar", release.primaryJar().browserDownloadUrl());
    }

    @Test
    @DisplayName("a sources-only release still yields an asset rather than nothing")
    void sourcesOnlyFallsBack() {
        JsonArray array = JsonParser.parseString("""
            [{"tag_name": "v1.0.0", "assets": [
                {"name": "mod-1.0.0-sources.jar", "browser_download_url": "https://x/s.jar"}]}]
            """).getAsJsonArray();
        ReleaseInfo release = GitHubReleaseSource.parseReleases(array, false).get(0);
        assertNotNull(release.primaryJar(), "a lone classifier jar is better than no update at all");
        assertEquals("mod-1.0.0-sources.jar", release.primaryJar().name());
    }

    @Test
    @DisplayName("a bare release tag takes its version from the release title")
    void bareTagReadsTheTitle() {
        // Releases are tagged v33 while the mod version is 18.16.0. Reading the tag as 33.0.0 makes
        // every check report an update, forever.
        JsonArray array = JsonParser.parseString("""
            [{"tag_name": "v33", "name": "Log Client 18.16.0"}]
            """).getAsJsonArray();
        ReleaseInfo release = GitHubReleaseSource.parseReleases(array, false).get(0);
        assertEquals(0, release.version().compareTo(Version.parse("18.16.0")),
            "expected 18.16.0 but was " + release.version());
        assertFalse(release.isNewerThan(Version.parse("18.16.0")),
            "an up-to-date install must not be offered the release it already has");
        assertTrue(release.isNewerThan(Version.parse("18.15.0")));
    }

    @Test
    @DisplayName("a dotted tag is still read from the tag, not the title")
    void dottedTagWins() {
        JsonArray array = JsonParser.parseString("""
            [{"tag_name": "v13.1.0", "name": "Some unrelated title"}]
            """).getAsJsonArray();
        ReleaseInfo release = GitHubReleaseSource.parseReleases(array, false).get(0);
        assertEquals(0, release.version().compareTo(Version.parse("13.1.0")),
            "expected 13.1.0 but was " + release.version());
    }

    @Test
    @DisplayName("a bare tag with no version in the title falls back to the tag")
    void bareTagWithoutTitleVersion() {
        JsonArray array = JsonParser.parseString("""
            [{"tag_name": "v5", "name": "V5"}]
            """).getAsJsonArray();
        ReleaseInfo release = GitHubReleaseSource.parseReleases(array, false).get(0);
        assertEquals(0, release.version().compareTo(Version.parse("5")),
            "expected 5 but was " + release.version());
    }
}
