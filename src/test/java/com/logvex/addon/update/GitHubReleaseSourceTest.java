package com.logvex.addon.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.logvex.addon.update.core.GitHubReleaseSource;
import com.logvex.addon.update.core.ReleaseInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
