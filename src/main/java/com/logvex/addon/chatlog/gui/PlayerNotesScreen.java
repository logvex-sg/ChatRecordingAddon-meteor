package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerNote;
import com.logvex.addon.chatlog.modules.PlayerNotesModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;

import java.util.List;

/**
 * View and edit player notes, with the observed activity shown next to each note.
 *
 * <p>A note and the activity are kept visually distinct on purpose: activity is measured from what
 * the client saw, a note is what the user typed. Where activity is unknown the screen says so rather
 * than showing a misleading zero.
 */
public class PlayerNotesScreen extends WindowScreen {
    private final PlayerNotesModule module;
    private String filter = "";
    private String newPlayer = "";
    private String newText = "";

    public PlayerNotesScreen(PlayerNotesModule module) {
        super(GuiThemes.get(), "Player Notes");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        WTable entry = add(theme.table()).expandX().widget();
        WTextBox player = entry.add(theme.textBox(newPlayer, "Player name")).expandCellX().widget();
        player.action = () -> newPlayer = player.get();
        WTextBox text = entry.add(theme.textBox(newText, "Note")).expandCellX().widget();
        text.action = () -> newText = text.get();
        WButton save = entry.add(theme.button("Save")).widget();
        save.action = () -> {
            if (newPlayer.isBlank()) {
                newPlayer = "";
                return;
            }
            module.note(newPlayer, newText);
            newPlayer = "";
            newText = "";
            render();
        };
        entry.row();

        WTextBox search = add(theme.textBox(filter, "Filter notes...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            render();
        };

        List<PlayerNote> notes = module.notes(filter);
        if (notes.isEmpty()) {
            add(theme.label(filter.isBlank() ? "No notes yet. Add one above." : "No notes match that filter."))
                .expandX().widget().color(GuiKit.MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Player")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Note")).expandCellX().widget().color(GuiKit.MUTED);
        table.add(theme.label("Activity")).widget().color(GuiKit.MUTED);
        table.add(theme.label("Updated")).widget().color(GuiKit.MUTED);
        table.row();

        long now = System.currentTimeMillis();
        for (PlayerNote note : notes) {
            table.add(theme.label(note.player())).widget().color(GuiKit.ACCENT);
            table.add(theme.label(GuiKit.truncate(note.text(), 60))).expandCellX().widget();
            table.add(theme.label(activitySummary(note.player()))).widget();
            table.add(theme.label(Messages.ago(note.updatedAt(), now))).widget().color(GuiKit.MUTED);
            WButton remove = table.add(theme.button("Remove")).widget();
            remove.action = () -> {
                module.removeNote(note.player());
                render();
            };
            table.row();
        }
    }

    /** A one-line activity summary, or an explicit "not observed" when the client has no data. */
    private String activitySummary(String player) {
        PlayerActivity activity = module.activity(player);
        if (activity == null) return "not observed";
        return activity.messageCount() + " msgs, " + activity.joinCount() + " joins";
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
