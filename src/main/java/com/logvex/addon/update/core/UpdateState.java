package com.logvex.addon.update.core;

/** The lifecycle state of the update system. */
public enum UpdateState {
    /** No check has run yet. */
    IDLE,
    /** A release check is in flight. */
    CHECKING,
    /** The installed build is the newest available. */
    UP_TO_DATE,
    /** A newer release is available but not yet downloaded. */
    AVAILABLE,
    /** A download is in flight. */
    DOWNLOADING,
    /** A verified update is saved on disk and ready to install. */
    READY,
    /** The last check or download failed. */
    FAILED
}
