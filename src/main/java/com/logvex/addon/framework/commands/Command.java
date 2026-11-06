package com.logvex.addon.framework.commands;

import com.logvex.addon.framework.utils.player.ChatUtils;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

import java.util.List;

/**
 * A chat command.
 *
 * <p>Subclasses describe their tree in {@link #build}; {@link #registerTo} installs it under the
 * command name and every alias. The {@code info}/{@code warning}/{@code error} helpers prefix output
 * with the command title so a reply is always attributable.
 */
public abstract class Command {
    protected static final int SINGLE_SUCCESS = com.mojang.brigadier.Command.SINGLE_SUCCESS;
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    private final String name;
    private final String title;
    private final String description;
    private final List<String> aliases;

    protected Command(String name, String description, String... aliases) {
        this.name = name;
        this.title = com.logvex.addon.framework.systems.modules.Module.nameToTitle(name);
        this.description = description;
        this.aliases = List.of(aliases);
    }

    protected static <T> RequiredArgumentBuilder<CommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    protected static LiteralArgumentBuilder<CommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public final void registerTo(com.mojang.brigadier.CommandDispatcher<CommandSource> dispatcher) {
        register(dispatcher, name);
        for (String alias : aliases) register(dispatcher, alias);
    }

    public void register(com.mojang.brigadier.CommandDispatcher<CommandSource> dispatcher, String name) {
        LiteralArgumentBuilder<CommandSource> builder = LiteralArgumentBuilder.literal(name);
        build(builder);
        dispatcher.register(builder);
    }

    public abstract void build(LiteralArgumentBuilder<CommandSource> builder);

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void info(Text message) {
        ChatUtils.sendMsg(title, message);
    }

    public void info(String message, Object... args) {
        ChatUtils.infoPrefix(title, message, args);
    }

    public void warning(String message, Object... args) {
        ChatUtils.warningPrefix(title, message, args);
    }

    public void error(String message, Object... args) {
        ChatUtils.errorPrefix(title, message, args);
    }

    @Override
    public String toString() {
        return "." + name;
    }
}
