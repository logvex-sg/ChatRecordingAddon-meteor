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

    /**
     * The version this release represents.
     *
     * <p>The tag is not always the version. Releases here are tagged with a bare counter
     * ({@code v33}) while the mod version lives in the title ({@code Log Client 18.16.0}). Reading
     * the tag would compare {@code 33.0.0} against an installed {@code 18.16.0} and report an
     * update on every check, forever. When the tag carries no dotted version the title is used
     * instead, and the tag remains the fallback for releases that have nothing better.
     */
    public Version version() {
        Version fromTag = Version.parse(tagName);
        if (!isBareTag(tagName)) return fromTag;
        Version fromName = Version.parse(name);
        return fromName.compareTo(Version.parse("0")) > 0 ? fromName : fromTag;
    }

    /** True for a tag like {@code v33}: a release counter with no dotted version in it. */
    private static boolean isBareTag(String tag) {
        return tag.trim().matches("(?i)v?\\d+");
    }

    /** True when this release is newer than {@code installed}. */
    public boolean isNewerThan(Version installed) {
        return version().isNewerThan(installed);
    }

    /**
     * The jar to install, or null when the release ships none.
     *
     * <p>Releases attach more than one jar — a sources jar alongside the mod — and GitHub returns
     * assets in alphabetical order, so "the first .jar" is the sources jar. Installing that would
     * replace the mod with source code. Classifier jars are therefore skipped, and a plain jar is
     * preferred over any other.
     */
    public ReleaseAsset primaryJar() {
        ReleaseAsset fallback = null;
        for (ReleaseAsset asset : assets) {
            String name = asset.name().toLowerCase(java.util.Locale.ROOT);
            if (!name.endsWith(".jar")) continue;
            if (isClassifierJar(name)) {
                if (fallback == null) fallback = asset;
                continue;
            }
            return asset;
        }
        return fallback;
    }

    /** True for the jars Gradle publishes beside the mod rather than as the mod. */
    private static boolean isClassifierJar(String name) {
        return name.endsWith("-sources.jar")
            || name.endsWith("-javadoc.jar")
            || name.endsWith("-dev.jar")
            || name.endsWith("-slim.jar");
    }
}
