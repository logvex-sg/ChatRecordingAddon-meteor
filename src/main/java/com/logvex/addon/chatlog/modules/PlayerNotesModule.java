package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.PlayerNote;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;

import java.util.List;

/**
 * User-written notes about players, plus a shortcut to see a player's observed activity.
 *
 * <p>Notes are the one piece of data the addon stores that a client cannot observe, so they are
 * always explicit user input. The module never infers a note; it only stores and displays what the
 * user typed. Notes persist with the module's settings.
 */
public class PlayerNotesModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final PlayerNote.Store store = new PlayerNote.Store();

    public PlayerNotesModule() {
        super(LogClient.CATEGORY, "player-notes",
            "Keeps notes about players and links them to observed activity.");
    }

    public PlayerNote.Store notes() {
        return store;
    }

    /** Adds or updates a note for {@code player}. */
    public PlayerNote note(String player, String text) {
        PlayerNote note = store.put(player, text, System.currentTimeMillis());
        if (note != null) info("Note saved for %s.", com.logvex.addon.common.Messages.player(player));
        return note;
    }

    public boolean removeNote(String player) {
        boolean removed = store.remove(player);
        if (removed) info("Note removed for %s.", com.logvex.addon.common.Messages.player(player));
        return removed;
    }

    /** Notes matching a filter, newest first. */
    public List<PlayerNote> notes(String filter) {
        return store.search(filter);
    }

    /** The observed activity for a player, or null when the client has never seen them act. */
    public com.logvex.addon.chatlog.core.PlayerActivity activity(String player) {
        return LogService.get().players().get(player);
    }

    @Override
    public String getInfoString() {
        return store.size() + " notes";
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Notes")).expandX().widget();
        open.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.PlayerNotesScreen(this));
        return list;
    }
}
