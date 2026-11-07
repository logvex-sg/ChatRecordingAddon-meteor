package com.logvex.addon.framework.systems.modules;

import java.util.Locale;

/**
 * Finds a module by its canonical name, its title, or a {@code kebab-case} spelling of either.
 *
 * <p>The registry matches a name exactly, which is right for the GUI but too strict for the companion
 * app: a web client naturally sends {@code chat-logger} for the module named {@code chat-logger}, but
 * a user typing into a URL or a script may write {@code ChatLogger} or {@code Chat Logger}. This
 * helper accepts all three without loosening the registry's own matching.
 */
public final class ModuleLookup {
    private ModuleLookup() {
    }

    /** The module whose name, title or normalised form matches, or null. */
    public static Module find(String query) {
        if (query == null || query.isBlank()) return null;
        String needle = normalise(query);
        for (Module module : Modules.get().getAll()) {
            if (normalise(module.name).equals(needle)
                || normalise(module.title).equals(needle)
                || module.name.equalsIgnoreCase(query)
                || module.title.equalsIgnoreCase(query)) {
                return module;
            }
        }
        return null;
    }

    /** Lower-cases and removes spaces, hyphens and underscores, so all spellings compare equal. */
    public static String normalise(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c != ' ' && c != '-' && c != '_') out.append(c);
        }
        return out.toString();
    }
}
