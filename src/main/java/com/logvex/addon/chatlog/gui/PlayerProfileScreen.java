package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.modules.WebhookRelayModule;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.systems.modules.Modules;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's observed activity, and a way to send it as an embed.
 *
 * <p>The list is built from the same in-memory player tracker the overview uses, so a name can be
 * picked here and forwarded to the shared webhook as a profile card without leaving the client.
 */
public class PlayerProfileScreen extends WindowScreen {
    private final WebhookRelayModule relay;
    private final LogService service = LogService.get();
    private String filter = "";
    private PlayerActivity selected;

    public PlayerProfileScreen(WebhookRelayModule relay) {
        super(GuiThemes.get(), "Player Profile");
        this.relay = relay;
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

        List<PlayerActivity> players = service.players().search(filter);
        if (players.isEmpty()) {
            add(theme.label("No player activity recorded.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Player")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Messages")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Deaths")).widget().color(GuiKit.MUTED);
        table.add(theme.label("")).widget();
        table.row();

        for (PlayerActivity activity : players) {
            boolean isSelected = selected != null && selected.name().equals(activity.name());
            table.add(theme.label((isSelected ? "\u25B8 " : "") + activity.name())).expandCellX().widget()
                .color(isSelected ? GuiKit.ACCENT : null);
            table.add(theme.label(String.valueOf(activity.messageCount()))).widget();
            table.add(theme.label(String.valueOf(activity.deathCount()))).widget();
            WButton pick = table.add(theme.button("Profile")).widget();
            pick.action = () -> {
                selected = activity;
                render();
            };
            table.row();
        }

        if (selected != null) {
            add(theme.horizontalSeparator(selected.name())).expandX();
            WVerticalList details = add(theme.verticalList()).expandX().widget();
            GuiKit.keyValue(details, theme, "Messages:", String.valueOf(selected.messageCount()));
            GuiKit.keyValue(details, theme, "Joins:", String.valueOf(selected.joinCount()));
            GuiKit.keyValue(details, theme, "Leaves:", String.valueOf(selected.leaveCount()));
            GuiKit.keyValue(details, theme, "Deaths:", String.valueOf(selected.deathCount()));
            GuiKit.keyValue(details, theme, "Sessions:", String.valueOf(selected.sessionCount()));
            if (selected.firstSeen() > 0) {
                GuiKit.keyValue(details, theme, "First seen:", ReportFormatting.time(selected.firstSeen()));
            }
            if (selected.lastSeen() > 0) {
                GuiKit.keyValue(details, theme, "Last seen:", ReportFormatting.time(selected.lastSeen()));
            }

            if (relay != null) {
                WButton send = add(theme.button("Send Profile Embed")).expandX().widget();
                send.action = () -> relay.sendProfile(selected.name());
            }
        }
    }

    /** The digest-style ranking used by the leaderboard embed, for reference in the GUI. */
    public List<ChatDigest.PlayerLine> ranking() {
        List<ChatDigest.PlayerLine> lines = new ArrayList<>();
        for (String name : service.snapshot().topPlayers()) {
            PlayerActivity activity = service.players().get(name);
            lines.add(new ChatDigest.PlayerLine(name, activity == null ? 0 : activity.messageCount(),
                0, 0, 0));
        }
        return lines;
    }

    @Override
    public boolean toClipboard() {
        return false;
    }

    @Override
    public boolean fromClipboard() {
        return false;
    }
}
