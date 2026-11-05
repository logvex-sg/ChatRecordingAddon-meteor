package com.logvex.addon.command;

import com.logvex.addon.bot.core.TaskType;

import java.util.Locale;

/**
 * Parses the small argument vocabulary the {@code .log} commands accept.
 *
 * <p>Kept separate from {@code LogCommand} on purpose: the command class extends Meteor's
 * {@code Command}, whose static initialiser needs a live Fabric environment. This helper touches
 * only Minecraft-free types, so the parsing rules can be tested without booting Minecraft.
 */
final class TaskArgs {
    private TaskArgs() {
    }

    /** Matches a user-typed task name case-insensitively, or null when there is no such task type. */
    static TaskType parseType(String value) {
        if (value == null) return null;
        String needle = value.trim().toUpperCase(Locale.ROOT);
        if (needle.isEmpty()) return null;
        for (TaskType type : TaskType.values()) {
            if (type.name().equals(needle)) return type;
        }
        return null;
    }

    /** The accepted task names, lower-cased and comma separated, for an error message. */
    static String typeNames() {
        StringBuilder sb = new StringBuilder();
        for (TaskType type : TaskType.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(type.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Normalises a user-typed item into a namespaced id.
     *
     * <p>{@code oak_log} becomes {@code minecraft:oak_log}; anything already containing a namespace is
     * lower-cased and left alone. Spaces become underscores so a human-readable name still resolves.
     */
    static String normaliseItem(String value) {
        if (value == null) return "";
        String trimmed = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (trimmed.isEmpty()) return "";
        return trimmed.indexOf(':') < 0 ? "minecraft:" + trimmed : trimmed;
    }
}
