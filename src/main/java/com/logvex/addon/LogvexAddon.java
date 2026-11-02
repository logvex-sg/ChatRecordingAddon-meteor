package com.logvex.addon;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

/**
 * Entry point for the Logvex addon. Hosts the SchematicDropper automation module and the
 * LiveChatRelay chat-report archive module, plus their HUD elements.
 */
public class LogvexAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Logvex");
    public static final HudGroup HUD_GROUP = new HudGroup("Logvex");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Logvex Addon");
        Modules.get().add(new com.logvex.addon.schematicdropper.SchematicDropperModule());
        Modules.get().add(new com.logvex.addon.chatrelay.LiveChatRelayModule());

        Hud.get().register(com.logvex.addon.schematicdropper.SchematicProgressHud.INFO);
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
