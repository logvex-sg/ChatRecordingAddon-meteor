package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.LogQuery;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.chatlog.modules.PlayerActivityModule;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.systems.modules.Modules;

import java.util.List;

/** Player activity overview: joins, leaves, deaths and message counts. */
public class PlayerOverviewScreen extends WindowScreen {
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private final PlayerActivityModule module;
    private final LogStorageService storage;
    private String filter = "";

    public PlayerOverviewScreen(PlayerActivityModule module, LogStorageService storage) {
        super(GuiThemes.get(), "Player Activity");
        this.module = module;
        this.storage = storage;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        var search = add(theme.textBox(filter, "Search player...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            taskAfterRender = this::render;
        };

        add(theme.horizontalSeparator()).expandX();

        if (module == null) {
            add(theme.label("The player activity module is not available.")).expandX();
            return;
        }

        List<PlayerActivity> players = module.players(filter);

        WTable actions = add(theme.table()).expandX().widget();
        WButton rebuild = actions.add(theme.button("Rebuild from logs")).widget();
        rebuild.action = () -> {
            if (storage != null) {
                storage.rebuildPlayersAsync(
                    com.logvex.addon.chatlog.mc.LogService.get().players(), this::render);
            }
        };
        WButton clear = actions.add(theme.button("Clear")).widget();
        clear.action = () -> {
            com.logvex.addon.chatlog.mc.LogService.get().players().clear();
            render();
        };

        add(theme.horizontalSeparator("Players (" + players.size() + ")")).expandX();

        if (players.isEmpty()) {
            add(theme.label("No player activity recorded.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Player")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Msgs")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Joins")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Leaves")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Deaths")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Last seen")).widget().color(GuiKit.MUTED);
        table.add(theme.label("")).widget();
        table.row();

        for (PlayerActivity activity : players) {
            table.add(theme.label(activity.name())).expandCellX().widget();
            table.add(theme.label(Long.toString(activity.messageCount()))).widget();
            table.add(theme.label(Long.toString(activity.joinCount()))).widget();
            table.add(theme.label(Long.toString(activity.leaveCount()))).widget();
            table.add(theme.label(Long.toString(activity.deathCount()))).widget();
            table.add(theme.label(ReportFormatting.time(activity.lastSeen()))).widget().color(GuiKit.MUTED);
            WButton view = table.add(theme.button("Chat")).widget();
            view.action = () -> {
                if (storage != null) {
                    mc.setScreen(new LogViewerScreen(storage,
                        LogQuery.builder().player(activity.name()).build(),
                        "Chat: " + activity.name()));
                }
            };
            WButton forget = table.add(theme.button("Forget")).widget();
            forget.action = () -> {
                com.logvex.addon.chatlog.mc.LogService.get().players().forget(activity.name());
                info("Forgot the tracked activity for " + activity.name() + ".");
                render();
            };
            table.row();
        }
    }

    private void info(String message) {
        com.logvex.addon.framework.utils.player.ChatUtils.info("%s", message);
    }
}
