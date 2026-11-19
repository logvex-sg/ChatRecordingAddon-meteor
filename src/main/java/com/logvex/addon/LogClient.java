package com.logvex.addon;

import com.logvex.addon.chatlog.hud.ActivityHud;
import com.logvex.addon.chatlog.hud.ChatLogHud;
import com.logvex.addon.chatlog.hud.StatsHud;
import com.logvex.addon.chatlog.mc.ChatRecorder;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.chatlog.modules.ChatMonitorModule;
import com.logvex.addon.chatlog.modules.KeywordAlertsModule;
import com.logvex.addon.chatlog.modules.PlayerActivityModule;
import com.logvex.addon.chatlog.modules.SessionStatsModule;
import com.mojang.logging.LogUtils;
import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudGroup;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

/**
 * Entry point for Log Client, a Meteor Client addon.
 *
 * <p>Meteor loads this class through the {@code meteor} entrypoint: it calls
 * {@link #onRegisterCategories()} before the module list is built, then {@link #onInitialize()} once
 * Meteor's systems are up. The addon registers its modules into Meteor's own registry, its HUD into
 * its own HUD manager, and its commands through {@link #registerCommands()} after Meteor's command
 * dispatcher exists — so every feature is driven from Meteor's GUI, keybinds, config and event bus
 * exactly like a built-in module.
 *
 * <p>The recorder and the shared services are subscribed once here, independently of module toggles,
 * so recording never depends on which module is enabled.
 */
public class LogClient extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Log Client");
    public static final HudGroup HUD_GROUP = new HudGroup("Log Client");

    private static final long SESSION_START_MS = System.currentTimeMillis();

    /** When this client session began, for uptime figures that outlive any single module. */
    public static long sessionStartMs() {
        return SESSION_START_MS;
    }

    @Override
    public void onInitialize() {
        LOG.info("Initializing Log Client");

        // Subscribe the recorder before any module so no early chat is missed. The chat filter, the
        // session timeline, player identities and world intelligence also subscribe once here: a
        // filter that only worked while a module was enabled would leak messages on toggle, and the
        // timeline must be complete from the first join. AddonEvents draws the HUD and flushes the
        // config on Meteor's own tick and render events.
        LogClientContext.EVENT_BUS.subscribe(new ChatRecorder());
        LogClientContext.EVENT_BUS.subscribe(new com.logvex.addon.framework.AddonEvents());
        // Subscribed before any command can be typed so `!log ...` is intercepted from the first
        // message, not only after some other event happens to build the router.
        LogClientContext.EVENT_BUS.subscribe(new com.logvex.addon.framework.AddonCommandRouter());
        com.logvex.addon.chatlog.mc.ChatFilterService.get().init(LogClientContext.EVENT_BUS);
        com.logvex.addon.chatlog.mc.SessionTimelineService.get().init(LogClientContext.EVENT_BUS);
        com.logvex.addon.chatlog.mc.PlayerIdentityService.get().init(LogClientContext.EVENT_BUS);
        com.logvex.addon.world.mc.WorldService.get().init(LogClientContext.EVENT_BUS);

        Modules.get().add(new ChatLoggerModule());
        Modules.get().add(new ChatMonitorModule());
        Modules.get().add(new KeywordAlertsModule());
        Modules.get().add(new PlayerActivityModule());
        Modules.get().add(new SessionStatsModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.ChatFilterModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.SessionTimelineModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.ChatDigestModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.WebhookMonitorModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.PlayerNotesModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.PlayerIdentityModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.SessionHistoryModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.WebhookRelayModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.MentionAlertsModule());

        // Automation: Minecraft-facing shells over the Minecraft-free controllers in
        // com.logvex.addon.automation.core. Each is independent and cleans up on deactivate.
        Modules.get().add(new com.logvex.addon.automation.modules.AutoCreativeModule());
        Modules.get().add(new com.logvex.addon.automation.modules.AutoEatModule());
        Modules.get().add(new com.logvex.addon.automation.modules.AutoToolModule());
        Modules.get().add(new com.logvex.addon.automation.modules.AutoReplenishModule());
        Modules.get().add(new com.logvex.addon.automation.modules.AutoSprintModule());
        Modules.get().add(new com.logvex.addon.automation.modules.SafeWalkModule());
        Modules.get().add(new com.logvex.addon.automation.modules.AntiAfkModule());
        Modules.get().add(new com.logvex.addon.automation.modules.ArmorGuardModule());
        Modules.get().add(new com.logvex.addon.automation.modules.AutoLogModule());
        Modules.get().add(new com.logvex.addon.automation.modules.AutoReconnectModule());
        Modules.get().add(new com.logvex.addon.automation.modules.ServerMonitorModule());

        // Keep the existing Schematic Dropper module and its HUD.
        Modules.get().add(new com.logvex.addon.schematicdropper.SchematicDropperModule());
        Hud.get().register(com.logvex.addon.schematicdropper.SchematicProgressHud.INFO);

        // Autonomous bot: the core is Minecraft-free; this is its Minecraft-facing shell.
        Modules.get().add(new com.logvex.addon.bot.BotModule());
        Hud.get().register(com.logvex.addon.bot.hud.BotStatusHud.INFO);

        // World intelligence: remembers discovered places, biomes, resources and chunks per world.
        Modules.get().add(new com.logvex.addon.world.modules.WorldIntelligenceModule());

        // Configurable chat assistant: keyword triggers, memory and memory commands. The decision
        // logic is Minecraft-free; this is its Minecraft-facing shell.
        Modules.get().add(new com.logvex.addon.assistant.modules.AssistantModule());
        Hud.get().register(com.logvex.addon.assistant.hud.AssistantHud.INFO);

        // Notifications are the shared output bus every other feature reports through.
        Modules.get().add(new com.logvex.addon.notify.NotificationModule());
        Hud.get().register(com.logvex.addon.notify.hud.NotificationHud.INFO);

        // Companion: a small local web server and app so a phone or another machine can watch and
        // control this client. Off by default; the server only runs while the module is enabled.
        Modules.get().add(new com.logvex.addon.companion.mc.CompanionModule());
        Hud.get().register(com.logvex.addon.companion.hud.CompanionHud.INFO);

        // Updates: checks GitHub Releases and downloads a newer jar on request. Off by default; all
        // network work runs on a daemon worker, and the module never installs anything itself.
        Modules.get().add(new com.logvex.addon.update.mc.UpdateModule());

        // A one-shot module that opens the addon's own window; bind it to a key in Meteor's GUI.
        Modules.get().add(new com.logvex.addon.gui.LogClientMenuModule());

        Hud.get().register(ChatLogHud.INFO);
        Hud.get().register(ActivityHud.INFO);
        Hud.get().register(StatsHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.WebhookStatusHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.ActivityGraphHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.RecentPlayersHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.PlayerIdentityHud.INFO);
        Hud.get().register(com.logvex.addon.stats.ServerStatsHud.INFO);

        // Rename the pre-rebrand data folder before anything reads or creates it, so an upgrade keeps
        // the recorded logs, identities, bot memory and config instead of starting empty.
        LogClientContext.migrateLegacyDataFolder();

        // Restore the saved configuration only after every module and HUD element exists.
        com.logvex.addon.framework.systems.config.ConfigManager.get().load();

        Runtime.getRuntime().addShutdownHook(new Thread(LogClient::shutdown, "log-client-shutdown"));
    }

    /**
     * Registers the addon commands once Meteor's command dispatcher is up.
     *
     * <p>Meteor runs {@code onInitialize} before {@code Commands.init()}, so adding a command there
     * would be wiped when the dispatcher is rebuilt. Declaring {@code Commands} as a dependency
     * orders this after that init; without it the order is not defined.
     */
    @meteordevelopment.meteorclient.utils.PostInit(
        dependencies = meteordevelopment.meteorclient.commands.Commands.class)
    public static void registerCommands() {
        meteordevelopment.meteorclient.commands.Commands.add(new com.logvex.addon.command.LogCommand());
        meteordevelopment.meteorclient.commands.Commands.add(new com.logvex.addon.command.BotCommand());
        meteordevelopment.meteorclient.commands.Commands.add(new com.logvex.addon.command.AssistantCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    /** Flushes the addon configuration and releases every service's resources at client shutdown. */
    public static void shutdown() {
        try {
            com.logvex.addon.framework.systems.config.ConfigManager.get().save();
        } catch (RuntimeException e) {
            LOG.warn("Could not flush the addon config on shutdown", e);
        }
        com.logvex.addon.chatlog.mc.SessionTimelineService.get().shutdown();
        com.logvex.addon.chatlog.mc.PlayerIdentityService.get().shutdown();
        com.logvex.addon.world.mc.WorldService.get().shutdown();
        com.logvex.addon.assistant.mc.AssistantService.get().shutdown();
        com.logvex.addon.ai.mc.AiService.get().shutdown();
        com.logvex.addon.update.mc.UpdateService.get().engine().shutdown();
    }

    @Override
    public String getPackage() {
        return "com.logvex.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("logvex-sg", "ChatRecordingAddon-meteor");
    }
}
