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
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

/**
 * Entry point for Log Addon.
 *
 * <p>Hosts the chat logging, monitoring, keyword alert, player activity and session statistics
 * modules, plus their HUD elements and the shared dashboard. The {@link ChatRecorder} is subscribed
 * once here so recording is independent of module toggles.
 */
public class LogAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Log Addon");
    public static final HudGroup HUD_GROUP = new HudGroup("Log Addon");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Log Addon");

        // Subscribe the recorder before any module so no early chat is missed.
        MeteorClient.EVENT_BUS.subscribe(new ChatRecorder());

        Modules.get().add(new ChatLoggerModule());
        Modules.get().add(new ChatMonitorModule());
        Modules.get().add(new KeywordAlertsModule());
        Modules.get().add(new PlayerActivityModule());
        Modules.get().add(new SessionStatsModule());

        // Keep the existing Schematic Dropper module and its HUD.
        Modules.get().add(new com.logvex.addon.schematicdropper.SchematicDropperModule());
        Hud.get().register(com.logvex.addon.schematicdropper.SchematicProgressHud.INFO);

        // Autonomous bot: the core is Minecraft-free, this is its Meteor shell.
        Modules.get().add(new com.logvex.addon.bot.BotModule());
        Hud.get().register(com.logvex.addon.bot.hud.BotStatusHud.INFO);

        Hud.get().register(ChatLogHud.INFO);
        Hud.get().register(ActivityHud.INFO);
        Hud.get().register(StatsHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
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
