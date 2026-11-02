package com.logvex.addon.schematicdropper.core;

/**
 * Callbacks for user-visible feedback. Implemented by the module; never called from a background
 * thread.
 */
public interface EngineListener {
    void onStateChanged(State state);

    void onConfirmed(String itemId, int amount, int remaining);

    void onFailure(FailureType type, String detail);

    void onCompleted(long totalDropped, long elapsedMs);

    void onFeedback(String message);
}
