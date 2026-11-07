package com.logvex.addon.update.core;

import java.util.List;

/**
 * One release as read from GitHub, reduced to what the client needs.
 *
 * @param tagName        the git tag, e.g. {@code v13.1.0}
 * @param name           the human title, falling back to the tag when GitHub gives none
 * @param body           the release notes (changelog) as markdown
 * @param preRelease     whether GitHub flags it as a pre-release
 * @param htmlUrl        the release page, opened when the user asks for details
 * @param publishedAt    an ISO-8601 timestamp, or empty when unknown
 * @param assets         the downloadable files attached to the release
 */
public record ReleaseInfo(
    String tagName,
    String name,
    String body,
    boolean preRelease,
    String htmlUrl,
    String publishedAt,
    List<ReleaseAsset> assets
) {
    public ReleaseInfo {
        assets = assets == null ? List.of() : List.copyOf(assets);
        tagName = tagName == null ? "" : tagName;
        name = name == null || name.isBlank() ? tagName : name;
        body = body == null ? "" : body;
        htmlUrl = htmlUrl == null ? "" : htmlUrl;
        publishedAt = publishedAt == null ? "" : publishedAt;
    }

    /** The version this release represents. */
    public Version version() {
        return Version.parse(tagName);
    }

    /** True when this release is newer than {@code installed}. */
    public boolean isNewerThan(Version installed) {
        return version().isNewerThan(installed);
    }

    /** The first asset whose name ends with {@code .jar}, or null when the release ships none. */
    public ReleaseAsset primaryJar() {
        for (ReleaseAsset asset : assets) {
            if (asset.name().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) return asset;
        }
        return null;
    }
}
