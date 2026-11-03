package com.logvex.addon.schematicdropper.core;

/**
 * Validates and resolves material names against the item registry.
 *
 * <p>Kept as an interface so the importer stays free of Minecraft types and can be unit tested
 * with a small fake registry.
 */
public interface ItemResolver {
    /** True when {@code itemId} is a well-formed namespaced id that exists in the registry. */
    boolean isValidId(String itemId);

    /**
     * Maps a human readable material name (e.g. {@code quartz pillar}) to a namespaced id,
     * or returns {@code null} when the name is unknown.
     */
    String resolveName(String name);

    /** A resolver that only checks id syntax, for callers without a registry. */
    static ItemResolver lenient() {
        return new ItemResolver() {
            @Override
            public boolean isValidId(String itemId) {
                return MaterialListImporter.isSyntacticallyValidId(itemId);
            }

            @Override
            public String resolveName(String name) {
                return null;
            }
        };
    }
}
