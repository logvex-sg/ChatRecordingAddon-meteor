package com.logvex.addon.framework.commands;

import com.logvex.addon.framework.pathing.PathManagers;
import com.logvex.addon.framework.utils.misc.PostInit;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The chat command registry and its dispatcher. */
public final class Commands {
    public static final CommandDispatcher<CommandSource> DISPATCHER = new CommandDispatcher<>();
    public static final List<Command> COMMANDS = new ArrayList<>();

    private Commands() {
    }

    @PostInit(dependencies = PathManagers.class)
    public static void init() {
        add(new com.logvex.addon.command.LogCommand());
        COMMANDS.sort(Comparator.comparing(Command::getName));
    }

    public static void add(Command command) {
        COMMANDS.removeIf(existing -> existing.getName().equals(command.getName()));
        command.registerTo(DISPATCHER);
        COMMANDS.add(command);
    }

    /** Runs {@code message} (without the leading dot) as a command. */
    public static void dispatch(String message) throws CommandSyntaxException {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        DISPATCHER.execute(message, mc.getNetworkHandler().getCommandSource());
    }

    public static Command get(String name) {
        for (Command command : COMMANDS) {
            if (command.getName().equals(name)) return command;
        }
        return null;
    }
}
