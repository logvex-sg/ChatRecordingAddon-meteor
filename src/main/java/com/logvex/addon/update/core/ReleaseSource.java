package com.logvex.addon.update.core;

import java.util.List;

/**
 * Where release metadata comes from.
 *
 * <p>Kept behind an interface so the update engine is testable without a network, and so a different
 * source (a mirror, a self-hosted endpoint) could be added without touching the engine.
 */
public interface ReleaseSource {
    /**
     * Fetches the releases, newest first as GitHub returns them.
     *
     * @param includePreReleases whether pre-releases should be included in the request
     * @throws Exception when the request fails; the caller reports it rather than crashing
     */
    List<ReleaseInfo> fetchReleases(boolean includePreReleases) throws Exception;

    /** A short human description of the source, for status output and errors. */
    String describe();
}
