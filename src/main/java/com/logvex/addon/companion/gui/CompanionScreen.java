package com.logvex.addon.companion.gui;

import com.logvex.addon.companion.mc.CompanionModule;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

import static com.logvex.addon.framework.Addon.mc;

/**
 * The companion's control panel: where the server is reachable, the token, and the ways to open or
 * copy the address.
 *
 * <p>The token is shown masked until the user reveals it, so it is not shoulder-surfed from a
 * screenshot, and the status line always reflects whether the server is actually listening rather
 * than whether the module is merely on.
 */
public class CompanionScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color RED = new Color(240, 120, 120);
    private static final Color GREY = new Color(170, 170, 170);

    private final CompanionModule module;
    private boolean revealToken;
    private WTable statusTable;

    public CompanionScreen(CompanionModule module) {
        this(GuiThemes.get(), module);
    }

    public CompanionScreen(GuiTheme theme, CompanionModule module) {
        super(theme, "Companion");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton copy = actions.add(theme.button("Copy address")).expandX().widget();
        copy.action = () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) client.keyboard.setClipboard(module.urlWithToken());
            module.info("Copied the companion address with its token.");
        };
        WButton copyPlain = actions.add(theme.button("Copy address only")).expandX().widget();
        copyPlain.action = () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) client.keyboard.setClipboard(module.url());
            module.info("Copied the companion address.");
        };
        WButton regenerate = actions.add(theme.button("New token")).expandX().widget();
        regenerate.action = () -> {
            module.regenerateToken();
            refresh();
        };

        WHorizontalList actions2 = add(theme.horizontalList()).expandX().widget();
        WButton toggleReveal = actions2.add(theme.button("Show token")).expandX().widget();
        toggleReveal.action = () -> {
            revealToken = !revealToken;
            toggleReveal.set(revealToken ? "Hide token" : "Show token");
            refresh();
        };
        WButton restart = actions2.add(theme.button("Restart server")).expandX().widget();
        restart.action = () -> {
            module.restart();
            refresh();
        };
        WButton settings = actions2.add(theme.button("Module settings...")).expandX().widget();
        settings.action = () -> mc.setScreen(new com.logvex.addon.gui.ModuleSettingsScreen(module, this));

        add(theme.horizontalSeparator()).expandX();
        statusTable = add(theme.table()).expandX().widget();
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 20 == 0) refresh();
    }

    private void refresh() {
        if (statusTable == null) return;
        statusTable.clear();

        boolean running = module.isRunning();
        statusTable.add(theme.label("Status")).expandCellX();
        statusTable.add(theme.label(running ? "Listening" : "Not listening")).expandCellX();
        statusTable.row();

        statusTable.add(theme.label("Address")).expandCellX();
        statusTable.add(theme.label(module.url())).expandCellX();
        statusTable.row();

        statusTable.add(theme.label("Port")).expandCellX();
        statusTable.add(theme.label(running ? String.valueOf(module.boundPort()) : "—")).expandCellX();
        statusTable.row();

        statusTable.add(theme.label("App clients")).expandCellX();
        statusTable.add(theme.label(String.valueOf(module.connectedClients()))).expandCellX();
        statusTable.row();

        statusTable.add(theme.label("Token")).expandCellX();
        statusTable.add(theme.label(revealToken ? module.accessToken() : mask(module.accessToken())))
            .expandCellX();
        statusTable.row();

        statusTable.add(theme.label("Remote access")).expandCellX();
        statusTable.add(theme.label(module.settings.get("allow-remote") != null
            && Boolean.TRUE.equals(module.settings.get("allow-remote").get())
            ? "allowed" : "loopback only")).expandCellX();
        statusTable.row();
    }

    private static String mask(String token) {
        if (token == null || token.isEmpty()) return "—";
        if (token.length() <= 6) return "••••••";
        return token.substring(0, 3) + "••••••••" + token.substring(token.length() - 3);
    }
}
