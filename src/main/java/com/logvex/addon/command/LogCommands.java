package com.logvex.addon.command;

import com.logvex.addon.LogClient;
import com.logvex.addon.bot.BotModule;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.notify.NotificationModule;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.List;

/**
 * Shared lookups for the {@code .log} commands.
 *
 * <p>Commands must never hold a module reference across calls: a module can be re-created at
 * on reload, so every accessor resolves the live instance through {@link Modules}. Keeping this in
 * one place means the command tree cannot accidentally cache a stale reference.
 */
final class LogCommands {
    private LogCommands() {
    }

    /** The live chat-logger module, or null when it is not registered. */
    static ChatLoggerModule logger() {
        return Modules.get().get(ChatLoggerModule.class);
    }

    /** The live bot module, or null when it is not registered. */
    static BotModule bot() {
        return Modules.get().get(BotModule.class);
    }

    /** The live world-intelligence module, or null when it is not registered. */
    static com.logvex.addon.world.modules.WorldIntelligenceModule world() {
        return Modules.get().get(com.logvex.addon.world.modules.WorldIntelligenceModule.class);
    }

    /** The live notification module, or null when it is not registered. */
    static NotificationModule notifications() {
        return Modules.get().get(NotificationModule.class);
    }

    /** Every module that belongs to the Log Client category, in registration order. */
    static List<Module> addonModules() {
        return Modules.get().getGroup(LogClient.CATEGORY);
    }

    /** The live webhook relay, or null when it is not registered. */
    static com.logvex.addon.chatlog.modules.WebhookRelayModule relay() {
        return Modules.get().get(com.logvex.addon.chatlog.modules.WebhookRelayModule.class);
    }

    /** The live module registry, for commands that resolve a module by type. */
    static Modules modules() {
        return Modules.get();
    }
}
