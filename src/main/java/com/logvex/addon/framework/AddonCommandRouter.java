package com.logvex.addon.framework;

import com.logvex.addon.common.CommandLine;
import com.logvex.addon.framework.systems.config.ClientConfig;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import com.logvex.addon.framework.SafeEventHandler;

/**
 * Runs this addon's own chat commands under the addon's prefix.
 *
 * <p>Meteor dispatches only lines that start with <em>its</em> prefix (a dot by default), so a line
 * beginning with the addon's own prefix would otherwise be sent to the server as ordinary chat and
 * the command would never run. This router closes that gap: it intercepts a line that starts with
 * the addon prefix and whose first word is a command this addon registered, runs it, and cancels the
 * send so the text never reaches the server.
 *
 * <p>It is deliberately narrow. A line is only consumed when the command name resolves to one of
 * ours, so every other message — plain chat, another mod's command, a server command — passes
 * through untouched. When the two prefixes are the same character Meteor has already handled the
 * line and this router never sees it.
 */
public final class AddonCommandRouter {
    /** The addon's own command names and aliases, lower-cased, built once on first use. */
    private static Set<String> ownNames;

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        SafeEventHandler.guard("AddonCommandRouter.onSendMessage", () -> {
            if (event == null || event.message == null) return;

            String body = CommandLine.body(event.message, ClientConfig.get().prefix(), ownNames());
            if (body == null) return;

            event.cancel();
            // Meteor echoes a dispatched command into chat history so the up arrow can recall it.
            // Matching that keeps an addon command behaving like every other one.
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.inGameHud != null) mc.inGameHud.getChatHud().addToMessageHistory(event.message);

            try {
                Commands.dispatch(body);
            } catch (CommandSyntaxException e) {
                ChatUtils.error(e.getMessage());
            } catch (RuntimeException e) {
                ChatUtils.error("That command failed: " + e.getMessage());
            }
        }).run();
    }

    /**
     * The command names and aliases this addon owns.
     *
     * <p>Read from the dispatcher rather than from a hand-written list: a list would fall out of
     * date the moment a command or alias is added, and a stale entry would silently stop routing
     * that command.
     */
    private static Set<String> ownNames() {
        // Rebuilt while empty as well as when unset: the router subscribes before Meteor's command
        // init, so a first build could otherwise cache an empty set and route nothing for the session.
        if (ownNames != null && !ownNames.isEmpty()) return ownNames;
        Set<String> names = new HashSet<>();
        for (Command command : Commands.COMMANDS) {
            if (!command.getClass().getName().startsWith("com.logvex.addon")) continue;
            names.add(command.getName().toLowerCase(Locale.ROOT));
            for (String alias : command.getAliases()) {
                names.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        ownNames = names;
        return names;
    }
}
