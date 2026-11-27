package com.logvex.addon.command;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.core.ReplyFormat.Field;
import com.logvex.addon.assistant.modules.AssistantModule;
import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.framework.utils.misc.ChatBlock;
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
    /**
     * Budget for an explicitly requested command list.
     *
     * <p>A group listing is asked for by name, so cutting it off mid-group defeats the request. The
     * larger cap still bounds the chat spam that {@link #MAX_LINES} exists to prevent.
     */
    private static final int MAX_LIST_LINES = 60;

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
        send(command, title, fields, footer, null);
    }

    /**
     * Sends {@code fields} as a list of {@code label - value} rows.
     *
     * <p>Used for command help, where every row is a whole usage line. The two-column box wastes
     * most of its width on a long label and then wraps the value mid-word, so a list of commands
     * reads better as a list than as a table of figures.
     */
    static void sendList(Command command, String title, List<Field> fields, String footer) {
        send(command, title, fields, footer, ReplyFormat.Style.LIST, MAX_LIST_LINES);
    }

    /**
     * Sends {@code fields} as a block in the user's style, but with the list line budget.
     *
     * <p>For answers that are a genuine list of records — queued tasks, past sessions, observed
     * renames. The default cap exists to stop a one-line answer turning into chat spam; here the
     * rows are the answer, so cutting them off at a dozen lines would hide most of it.
     */
    static void sendLong(Command command, String title, List<Field> fields, String footer) {
        send(command, title, fields, footer, null, MAX_LIST_LINES);
    }

    private static void send(Command command, String title, List<Field> fields, String footer,
                             ReplyFormat.Style preferred) {
        send(command, title, fields, footer, preferred, MAX_LINES);
    }

    private static void send(Command command, String title, List<Field> fields, String footer,
                             ReplyFormat.Style preferred, int maxLines) {
        if (command == null || fields == null || fields.isEmpty()) return;

        AssistantModule module = Modules.get().get(AssistantModule.class);
        int width = module == null ? ReplyFormat.DEFAULT_WIDTH : module.replyWidth();
        ReplyFormat.Style style = preferred;
        if (style == null) {
            style = module == null ? ReplyFormat.Style.BOX : module.replyStyle();
        }
        if (style == null) style = ReplyFormat.Style.BOX;

        Rendered rendered = renderForChat(title, fields, footer, style, width, maxLines);
        if (LogClientContext.mc == null || LogClientContext.mc.inGameHud == null) {
            // No chat box to print into (e.g. run from a menu), so fall back to the command's own
            // output rather than silently dropping the answer.
            for (String line : rendered.lines()) command.info("%s", line);
        } else {
            ChatBlock.print(rendered.lines());
        }
        if (rendered.omitted() > 0) {
            command.info("... %d more line%s; %sprefix copies the full list to the clipboard.",
                rendered.omitted(), rendered.omitted() == 1 ? "" : "s", prefixOrEmpty());
        }
    }

    /** A block ready to send, with how many lines the cap dropped. */
    record Rendered(List<String> lines, int omitted) {
    }

    /**
     * Renders a block so it survives the chat line cap.
     *
     * <p>The cap has to be applied to the <em>fields</em> and the block re-rendered, never by
     * cutting the rendered lines: a box cut at an arbitrary row ends on a bare {@code │} with no
     * closing rule, which reads as a message that failed to send. {@link ReplyFormat#renderCapped}
     * does that work; this method exists so the choice can be tested without a live chat box.
     */
    static Rendered renderForChat(String title, List<Field> fields, String footer,
                                  ReplyFormat.Style style, int width, int maxLines) {
        ReplyFormat.Embed embed = new ReplyFormat.Embed(title, fields, footer, style);
        List<String> full = ReplyFormat.render(embed, width);
        List<String> capped = ReplyFormat.renderCapped(embed, width, maxLines);
        return new Rendered(capped, Math.max(0, full.size() - capped.size()));
    }

    /** The addon prefix for hint text, or empty when the config is not reachable. */
    private static String prefixOrEmpty() {
        try {
            return com.logvex.addon.framework.systems.config.ClientConfig.get().prefix();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
