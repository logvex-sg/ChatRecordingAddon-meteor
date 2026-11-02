package com.logvex.addon.schematicdropper.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-material progress numbers derived from confirmed item transfers only.
 * Never counts attempted operations.
 */
public record MaterialProgress(
    String itemId,
    String displayName,
    int required,
    int confirmed,
    int remaining,
    int inventory,
    int missing
) {
    public boolean complete() {
        return required > 0 && confirmed >= required;
    }

    public double fraction() {
        return required <= 0 ? 0 : Math.min(1.0, (double) confirmed / required);
    }
}
