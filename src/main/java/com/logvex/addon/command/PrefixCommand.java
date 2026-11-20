package com.logvex.addon.command;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.common.CommandCatalog;
import com.logvex.addon.framework.systems.config.ClientConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code !prefix} — puts this addon's commands on the clipboard.
 *
 * <p>The point is to stop a user retyping a command tree from a chat window that scrolls away.
 * Running it with no argument copies every command, one per line, with the prefix already applied,
 * so the text can be pasted straight back into chat or into a Discord message. Naming a command
 * copies only that command's forms.
 *
 * <p>The list itself lives in {@link CommandCatalog}, so what is copied is the same list the help
 * prints and the window shows.
 */
public class PrefixCommand extends Command {
    public PrefixCommand() {
        super("prefix", "Copies this addon's commands to the clipboard.", "prefixes", "cmds");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            copyAll();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("all").executes(context -> {
            copyAll();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("list").executes(context -> {
            showAll();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("copy").executes(context -> {
            copyAll();
            return SINGLE_SUCCESS;
        }));

        builder.then(argument("command", StringArgumentType.greedyString()).executes(context -> {
            copyOne(StringArgumentType.getString(context, "command"));
            return SINGLE_SUCCESS;
        }));
    }

    /** Copies every command, prefix applied, and reports how many. */
    private void copyAll() {
        String prefix = ClientConfig.get().prefix();
        String block = CommandCatalog.copyableBlock(prefix);
        if (block.isBlank()) {
            warning("There are no commands to copy.");
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.keyboard == null) {
            error("No clipboard available right now.");
            return;
        }
        mc.keyboard.setClipboard(block);
        info("Copied %d commands to the clipboard, using prefix '%s'.",
            CommandCatalog.count(), prefix);
        info("Paste them anywhere; each line is already a runnable command.");
    }

    /** Copies just the forms of one command. */
    private void copyOne(String name) {
        List<CommandCatalog.Entry> matches = CommandCatalog.find(name);
        if (matches.isEmpty()) {
            warning("No command called '%s'. Run %shelp to see the groups.",
                name, ClientConfig.get().prefix());
            return;
        }
        String prefix = ClientConfig.get().prefix();
        List<String> lines = new ArrayList<>();
        for (CommandCatalog.Entry entry : matches) lines.add(CommandCatalog.prefixed(prefix, entry));

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.keyboard == null) {
            error("No clipboard available right now.");
            return;
        }
        mc.keyboard.setClipboard(String.join("\n", lines));
        info("Copied %d form%s of '%s'.", lines.size(), lines.size() == 1 ? "" : "s", name);
    }

    /** Prints the whole list instead of copying it, for when the clipboard is not wanted. */
    private void showAll() {
        String prefix = ClientConfig.get().prefix();
        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (CommandCatalog.Entry entry : CommandCatalog.entries()) {
            fields.add(new ReplyFormat.Field(CommandCatalog.prefixed(prefix, entry), entry.description()));
        }
        CommandOutput.send(this, "All commands (" + CommandCatalog.count() + ")", fields,
            prefix + "prefix copies these to the clipboard");
    }
}
