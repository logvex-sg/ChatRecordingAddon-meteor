package com.logvex.addon.schematicdropper.core;

/**
 * Callbacks for user-visible acquisition feedback. Implemented by the module and always invoked
 * from the client thread.
 */
public interface AcquisitionListener {
    void onStateChanged(AcquisitionState state);

    /** A material reached its required quantity. */
    void onAcquired(String itemId, int amount);

    /** A material failed; {@code fatal} marks a failure that stops the whole workflow. */
    void onFailure(FailureType type, String detail, boolean fatal);

    void onCompleted(int materials, long totalAcquired, long elapsedMs);

    /** Low-severity status line, already phrased for chat. */
    void onFeedback(String message);
}
