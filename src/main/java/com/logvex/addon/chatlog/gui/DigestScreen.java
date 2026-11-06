package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.ChatDigest;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.modules.ChatDigestModule;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;

import java.util.List;

/** A read-only view of the current chat digest, with a refresh control. */
public class DigestScreen extends WindowScreen {
    private final ChatDigestModule module;

    public DigestScreen(ChatDigestModule module) {
        super(GuiThemes.get(), "Chat Digest");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        WButton refresh = add(theme.button("Refresh")).expandX().widget();
        refresh.action = this::render;

        ChatDigest.Summary summary = module.summary();
        if (summary.isEmpty()) {
            add(theme.label("No messages in the digest window.")).expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WVerticalList overview = add(theme.verticalList()).expandX().widget();
        GuiKit.keyValue(overview, theme, "Messages:", String.valueOf(summary.totalMessages()));
        GuiKit.keyValue(overview, theme, "Players:", String.valueOf(summary.uniquePlayers()));
        GuiKit.keyValue(overview, theme, "Joins:", String.valueOf(summary.joinEvents()));
        GuiKit.keyValue(overview, theme, "Leaves:", String.valueOf(summary.leaveEvents()));
        GuiKit.keyValue(overview, theme, "Deaths:", String.valueOf(summary.deathEvents()));

        WTable types = add(theme.table()).expandX().widget();
        types.add(theme.label("Type")).widget().color(GuiKit.MUTED);
        types.add(theme.label("Count")).widget().color(GuiKit.MUTED);
        types.row();
        for (MessageType type : MessageType.values()) {
            long count = summary.count(type);
            if (count == 0) continue;
            types.add(theme.label(type.toString())).widget();
            types.add(theme.label(String.valueOf(count))).widget();
            types.row();
        }

        if (!summary.topPlayers().isEmpty()) {
            add(theme.label("Most active players")).expandX().widget().color(GuiKit.ACCENT);
            WTable players = add(theme.table()).expandX().widget();
            players.add(theme.label("Player")).expandCellX().widget().color(GuiKit.MUTED);
            players.add(theme.label("Messages")).widget().color(GuiKit.MUTED);
            players.add(theme.label("Joins")).widget().color(GuiKit.MUTED);
            players.add(theme.label("Deaths")).widget().color(GuiKit.MUTED);
            players.row();
            for (ChatDigest.PlayerLine line : summary.topPlayers()) {
                players.add(theme.label(line.name())).expandCellX().widget();
                players.add(theme.label(String.valueOf(line.messages()))).widget();
                players.add(theme.label(String.valueOf(line.joins()))).widget();
                players.add(theme.label(String.valueOf(line.deaths()))).widget().color(GuiKit.BAD);
                players.row();
            }
        }
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
