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
import com.logvex.addon.framework.ModuleAudit;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudGroup;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

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
        Modules.get().add(new com.logvex.addon.chatlog.modules.ChatTimestampsModule());
        Modules.get().add(new com.logvex.addon.chatlog.modules.RepeatCollapseModule());

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
        Modules.get().add(new com.logvex.addon.automation.modules.AutoLoginModule());
        Modules.get().add(new com.logvex.addon.automation.modules.WatchlistAlertsModule());
        Modules.get().add(new com.logvex.addon.automation.modules.DeathTrackerModule());
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

        checkModuleNames();

        // Restore the saved configuration only after every module and HUD element exists.
        com.logvex.addon.framework.systems.config.ConfigManager.get().load();

        Runtime.getRuntime().addShutdownHook(new Thread(LogClient::shutdown, "log-client-shutdown"));
    }

    /**
     * Reports every module whose name is claimed by more than one module.
     *
     * <p>Meteor's registry is keyed by class but de-duplicated by name: {@code Modules.add} drops any
     * existing module that shares the new one's name. An addon that reuses a Meteor name therefore
     * deletes Meteor's module, and Meteor's own code — which looks modules up by class — then reads
     * null. That is not a cosmetic clash: {@code DisconnectedScreenMixin} initialises a field from
     * {@code Modules.get().get(AutoReconnect.class).time}, so losing Meteor's {@code AutoReconnect}
     * crashed the client on every disconnect. Names are checked after registration because Meteor
     * builds its own modules before any addon's {@code onInitialize} runs.
     */
    private static void checkModuleNames() {
        List<String> names = new ArrayList<>();
        for (Module module : Modules.get().getAll()) names.add(module.name);

        for (ModuleAudit.Clash clash : ModuleAudit.clashes(names)) {
            LOG.error("Module name '{}' is claimed by {} modules. Meteor de-duplicates by name, so"
                + " one of them was discarded — rename ours to avoid breaking the other addon.",
                clash.name(), clash.count());
        }
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
        meteordevelopment.meteorclient.commands.Commands.add(new com.logvex.addon.command.PrefixCommand());
        meteordevelopment.meteorclient.commands.Commands.add(new com.logvex.addon.command.ChatQueryCommand());
        meteordevelopment.meteorclient.commands.Commands.add(new com.logvex.addon.command.AutoLoginCommand());
        meteordevelopment.meteorclient.commands.Commands.add(new com.logvex.addon.command.DeathsCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    /**
     * Flushes the addon configuration and releases every service's resources at client shutdown.
     *
     * <p>Each step is isolated. This runs from a JVM shutdown hook, where a thrown exception aborts
     * the remainder of the hook: without the guards, one failing service would stop every later
     * service from writing its data, and the loss would be silent.
     */
    public static void shutdown() {
        flush("config", () -> com.logvex.addon.framework.systems.config.ConfigManager.get().save());
        flush("session-timeline", () -> com.logvex.addon.chatlog.mc.SessionTimelineService.get().shutdown());
        flush("player-identity", () -> com.logvex.addon.chatlog.mc.PlayerIdentityService.get().shutdown());
        flush("world", () -> com.logvex.addon.world.mc.WorldService.get().shutdown());
        flush("assistant", () -> com.logvex.addon.assistant.mc.AssistantService.get().shutdown());
        flush("ai", () -> com.logvex.addon.ai.mc.AiService.get().shutdown());
        flush("update", () -> com.logvex.addon.update.mc.UpdateService.get().engine().shutdown());
    }

    /** Runs one shutdown step, logging rather than propagating a failure. */
    private static void flush(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Could not flush '{}' on shutdown; continuing with the rest", what, e);
        }
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
