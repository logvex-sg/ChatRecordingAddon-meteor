package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.PlayerIdentity;
import com.logvex.addon.chatlog.core.PlayerIdentityTracker;
import com.logvex.addon.chatlog.modules.PlayerIdentityModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.player.ChatUtils;

import java.util.List;

/**
 * Player identity browser: observed names per account and a watchlist.
 *
 * <p>Every value shown was observed from the server player list. An account with a single name is
 * shown as "no change", never as an invented history.
 */
public class PlayerIdentityScreen extends WindowScreen {
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();

    private final PlayerIdentityModule module;
    private String filter = "";
    private boolean renamedOnly;

    public PlayerIdentityScreen(PlayerIdentityModule module) {
        super(GuiThemes.get(), "Player Identity");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        if (module == null) {
            add(theme.label("The player identity module is not available.")).expandX();
            return;
        }

        var search = add(theme.textBox(filter, "Search by name...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            taskAfterRender = this::render;
        };

        WTable controls = add(theme.table()).expandX().widget();
        WButton toggle = controls.add(theme.button(renamedOnly ? "Show All" : "Renamed Only")).widget();
        toggle.action = () -> {
            renamedOnly = !renamedOnly;
            taskAfterRender = this::render;
        };
        WButton clear = controls.add(theme.button("Clear Index")).widget();
        clear.action = () -> {
            com.logvex.addon.chatlog.mc.LogService.get().identities().clear();
            info("Identity index cleared.");
            taskAfterRender = this::render;
        };

        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Accounts:",
            Integer.toString(com.logvex.addon.chatlog.mc.LogService.get().identities().size()));
        GuiKit.keyValue(add(theme.verticalList()).expandX().widget(), theme, "Renames:",
            Integer.toString(com.logvex.addon.chatlog.mc.LogService.get().identities().renameCount()));

        List<PlayerIdentity> identities = renamedOnly ? module.renamed() : module.identities(filter);
        if (!filter.isBlank() && renamedOnly) {
            identities = identities.stream()
                .filter(identity -> identity.currentName().toLowerCase(java.util.Locale.ROOT).contains(filter.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        }

        add(theme.horizontalSeparator("Accounts (" + identities.size() + ")")).expandX();

        if (identities.isEmpty()) {
            add(theme.label("No accounts observed yet.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Current name")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Previous name")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Changes")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Last seen")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Watch")).widget().color(GuiKit.MUTED);
        table.row();

        long now = System.currentTimeMillis();
        for (PlayerIdentity identity : identities) {
            table.add(theme.label(identity.currentName())).expandCellX().widget();
            String previous = identity.previousName();
            table.add(theme.label(previous.isEmpty() ? "-" : previous)).widget()
                .color(previous.isEmpty() ? GuiKit.MUTED : GuiKit.ACCENT);
            table.add(theme.label(Integer.toString(identity.changeCount()))).widget();
            table.add(theme.label(identity.lastSeen() > 0 ? Messages.ago(identity.lastSeen(), now) : "-"))
                .widget().color(GuiKit.MUTED);

            boolean watched = com.logvex.addon.chatlog.mc.LogService.get().watchlist().contains(identity.currentName());
            WButton watch = table.add(theme.button(watched ? "Unwatch" : "Watch")).widget();
            watch.action = () -> {
                var watchlist = com.logvex.addon.chatlog.mc.LogService.get().watchlist();
                if (watched) watchlist.remove(identity.currentName());
                else watchlist.add(identity.currentName());
                taskAfterRender = this::render;
            };
            table.row();
        }

        add(theme.horizontalSeparator("Recent renames")).expandX();
        List<PlayerIdentityTracker.Rename> renames = module.renames();
        if (renames.isEmpty()) {
            add(theme.label("No renames observed.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }
        for (PlayerIdentityTracker.Rename rename : renames.subList(0, Math.min(10, renames.size()))) {
            add(theme.label(rename.from() + " \u2192 " + rename.to()
                + "   (" + Messages.ago(rename.atMs(), now) + ")")).expandX();
        }
    }

    private void info(String message) {
        ChatUtils.info("%s", message);
    }
}
