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
import com.logvex.addon.framework.Addon;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudGroup;
import com.logvex.addon.framework.systems.modules.Category;
import com.logvex.addon.framework.systems.modules.Modules;
import org.slf4j.Logger;

/**
 * Entry point for Log Addon.
 *
 * <p>Hosts the chat logging, monitoring, keyword alert, player activity and session statistics
 * modules, plus their HUD elements and the shared dashboard. The {@link ChatRecorder} is subscribed
 * once here so recording is independent of module toggles.
 *
 * <p>Standalone by design: this class owns its own module registry, event bus, HUD, command tree and
 * configuration. Nothing here depends on another mod being installed.
 */
public class LogAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Log Addon");
    public static final HudGroup HUD_GROUP = new HudGroup("Log Addon");

    public void onInitialize() {
        LOG.info("Initializing Log Addon");

        // Subscribe the recorder before any module so no early chat is missed. The chat filter and
        // the session timeline also subscribe once here: a filter that only worked while a module was
        // enabled would leak messages on toggle, and the timeline must be complete from the first join.
        Addon.EVENT_BUS.subscribe(new ChatRecorder());
        com.logvex.addon.chatlog.mc.ChatFilterService.get().init(Addon.EVENT_BUS);
        com.logvex.addon.chatlog.mc.SessionTimelineService.get().init(Addon.EVENT_BUS);
        com.logvex.addon.chatlog.mc.PlayerIdentityService.get().init(Addon.EVENT_BUS);

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

        // Notifications are the shared output bus every other feature reports through.
        Modules.get().add(new com.logvex.addon.notify.NotificationModule());
        Hud.get().register(com.logvex.addon.notify.hud.NotificationHud.INFO);

        Hud.get().register(ChatLogHud.INFO);
        Hud.get().register(ActivityHud.INFO);
        Hud.get().register(StatsHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.WebhookStatusHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.ActivityGraphHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.RecentPlayersHud.INFO);
        Hud.get().register(com.logvex.addon.chatlog.hud.PlayerIdentityHud.INFO);
    }

    /**
     * Runs the whole addon bootstrap: modules, HUD elements, the pathing backend and the command
     * tree. Called once from the client entrypoint.
     */
    public static void initialize() {
        LogAddon addon = new LogAddon();
        addon.onInitialize();
        addon.onRegisterCategories();

        com.logvex.addon.framework.pathing.PathManagers.init();
        com.logvex.addon.framework.commands.Commands.init();

        // Restore the saved configuration only after every module and HUD element exists.
        com.logvex.addon.framework.systems.config.ConfigManager.get().load();
    }

    /** Releases every module's resources and flushes the configuration. */
    public static void shutdown() {
        com.logvex.addon.framework.systems.config.ConfigManager.get().save();
        Modules.get().disableAll();
        com.logvex.addon.chatlog.mc.SessionTimelineService.get().shutdown();
        com.logvex.addon.chatlog.mc.PlayerIdentityService.get().shutdown();
    }

    /** Persists the configuration when it has changed. Called periodically from the tick loop. */
    public static void tick() {
        com.logvex.addon.framework.systems.config.ConfigManager.get().saveIfDirty();
    }

    public void onRegisterCategories() {
        Modules.get().registerCategory(CATEGORY);
    }

    /** The addon's package root, exposed for diagnostics and the About screen. */
    public String getPackage() {
        return "com.logvex.addon";
    }

    /** The addon's source repository, exposed for the About screen. */
    public String getRepo() {
        return "https://github.com/logvex-sg/ChatRecordingAddon-meteor";
    }
}
