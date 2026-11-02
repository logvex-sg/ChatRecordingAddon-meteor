package com.logvex.addon.chatrelay.core;

/** Raised when an archive file cannot be decoded or fails its integrity check. */
public class ArchiveCorruptedException extends Exception {
    public ArchiveCorruptedException(String message) {
        super(message);
    }

    public ArchiveCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
