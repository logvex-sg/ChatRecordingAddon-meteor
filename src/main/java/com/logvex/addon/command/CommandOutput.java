package com.logvex.addon.command;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.ReplyFormat.Field;
import com.logvex.addon.assistant.modules.AssistantModule;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.List;

/**
 * Prints a summary as an aligned block instead of a run of loose lines.
 *
 * <p>Shared by every command that answers with figures: the layout, the line cap and the user's
 * chosen reply style have to be identical everywhere, or the same answer looks different depending
 * on which command printed it. Style and width come from the assistant module so a command follows
 * the same setting the assistant's own replies do.
 */
final class CommandOutput {
    /** How many block lines one answer may occupy before the rest is summarised. */
    private static final int MAX_LINES = 12;

    private CommandOutput() {
    }

    /** Sends {@code fields} as a block under {@code title}. */
    static void send(Command command, String title, List<Field> fields) {
        send(command, title, fields, "");
    }

    /**
     * Sends {@code fields} as a block under {@code title}, with {@code footer} on the closing rule.
     *
     * <p>Nothing is printed for an empty field list: a block with no content is two rules and a
     * title, which reads as a broken message rather than an answer.
     */
    static void send(Command command, String title, List<Field> fields, String footer) {
        if (command == null || fields == null || fields.isEmpty()) return;

        AssistantModule module = Modules.get().get(AssistantModule.class);
        int width = module == null ? ReplyFormat.DEFAULT_WIDTH : module.replyWidth();
        ReplyFormat.Style style = module == null ? ReplyFormat.Style.BOX : module.replyStyle();
        if (style == null) style = ReplyFormat.Style.BOX;

        List<String> lines = ReplyFormat.render(new ReplyFormat.Embed(title, fields, footer, style), width);
        int shown = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) command.info("%s", lines.get(i));
        if (lines.size() > MAX_LINES) {
            command.info("... %d more lines; ask for one section to see them all.",
                lines.size() - MAX_LINES);
        }
    }
}
