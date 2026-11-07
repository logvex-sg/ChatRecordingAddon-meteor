package com.logvex.addon.update.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads releases from the public GitHub Releases API.
 *
 * <p>Only the unauthenticated, read-only endpoint is used: no token, no credentials and no data are
 * sent beyond the request itself. GitHub rate-limits unauthenticated calls, so a rate-limit response
 * is reported as a clear message ("try again later") rather than a stack trace.
 *
 * <p>The JSON is parsed defensively; a field that is missing or of the wrong type is treated as
 * absent, so a change in the API shape degrades to "no update" instead of a crash.
 */
public final class GitHubReleaseSource implements ReleaseSource {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_RELEASES = 30;

    private final String owner;
    private final String repo;
    private final String userAgent;

    public GitHubReleaseSource(String owner, String repo, String userAgent) {
        this.owner = owner;
        this.repo = repo;
        this.userAgent = userAgent == null ? "Log Client" : userAgent;
    }

    @Override
    public List<ReleaseInfo> fetchReleases(boolean includePreReleases) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo
            + "/releases?per_page=" + MAX_RELEASES;

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            int status = connection.getResponseCode();
            if (status == 403 || status == 429) {
                throw new IllegalStateException("GitHub rate limit reached; try again later.");
            }
            if (status == 404) {
                throw new IllegalStateException("The release repository was not found.");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("GitHub returned HTTP " + status + ".");
            }

            try (InputStream in = connection.getInputStream();
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root == null || !root.isJsonArray()) return List.of();
                return parseReleases(root.getAsJsonArray(), includePreReleases);
            }
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public String describe() {
        return "github.com/" + owner + "/" + repo;
    }

    /** Package-private so the parsing rules can be unit-tested without a network. */
    public static List<ReleaseInfo> parseReleases(JsonArray array, boolean includePreReleases) {
        List<ReleaseInfo> releases = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            if (boolOr(object, "draft", false)) continue;
            boolean preRelease = boolOr(object, "prerelease", false);
            if (preRelease && !includePreReleases) continue;
            releases.add(parseRelease(object, preRelease));
        }
        return releases;
    }

    private static ReleaseInfo parseRelease(JsonObject object, boolean preRelease) {
        return new ReleaseInfo(
            stringOr(object, "tag_name", ""),
            stringOr(object, "name", ""),
            stringOr(object, "body", ""),
            preRelease,
            stringOr(object, "html_url", ""),
            stringOr(object, "published_at", ""),
            parseAssets(object));
    }

    private static List<ReleaseAsset> parseAssets(JsonObject object) {
        List<ReleaseAsset> assets = new ArrayList<>();
        JsonElement assetsElement = object.get("assets");
        if (assetsElement == null || !assetsElement.isJsonArray()) return assets;
        for (JsonElement element : assetsElement.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject asset = element.getAsJsonObject();
            String name = stringOr(asset, "name", "");
            String url = stringOr(asset, "browser_download_url", "");
            long size = longOr(asset, "size", -1);
            String sha = stringOr(asset, "sha256", "");
            if (name.isEmpty() || url.isEmpty()) continue;
            assets.add(new ReleaseAsset(name, url, size, sha));
        }
        return assets;
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean boolOr(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longOr(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
