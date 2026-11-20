package com.logvex.addon.common;

import java.util.Collection;
import java.util.Locale;

/**
 * Decides whether a chat line is one of this addon's commands under the addon's own prefix.
 *
 * <p>Kept free of Minecraft and Meteor types so the rule can be tested directly: getting it wrong
 * either swallows ordinary chat or leaves a command unsendable, and both are easy to miss in game.
 */
public final class CommandLine {
    private CommandLine() {
    }

    /**
     * The command body when {@code message} is one of {@code ownNames} under {@code prefix}.
     *
     * <p>The prefix must match at the very start of the line, the command name is compared
     * case-insensitively, and the remainder is returned verbatim so arguments keep their case. A
     * line that is only the prefix, or whose first word is not one of ours, is not a command.
     *
     * @param message  the line the player is about to send
     * @param prefix   the addon's command prefix, e.g. {@code !}
     * @param ownNames this addon's command names and aliases, any case
     * @return the text after the prefix, ready to dispatch, or null when the line is not ours
     */
    public static String body(String message, String prefix, Collection<String> ownNames) {
        if (message == null || prefix == null || prefix.isEmpty() || ownNames == null) return null;
        if (!message.startsWith(prefix)) return null;

        String body = message.substring(prefix.length()).strip();
        if (body.isEmpty()) return null;

        String word = firstWord(body);
        String name = word.toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return null;
        for (String own : ownNames) {
            if (own != null && own.toLowerCase(Locale.ROOT).equals(name)) {
                // The name is folded to lower case because command literals are registered that
                // way: matching `!Log` and then dispatching it as typed would find no command.
                // Arguments keep their case, since they are values rather than command names.
                return name + body.substring(word.length());
            }
        }
        return null;
    }

    /** The first whitespace-separated word of {@code text}, or the whole text when it has none. */
    public static String firstWord(String text) {
        if (text == null) return "";
        String stripped = text.strip();
        for (int i = 0; i < stripped.length(); i++) {
            if (Character.isWhitespace(stripped.charAt(i))) return stripped.substring(0, i);
        }
        return stripped;
    }
}
