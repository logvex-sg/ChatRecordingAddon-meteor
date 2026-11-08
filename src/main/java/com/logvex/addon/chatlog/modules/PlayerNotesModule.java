package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.PlayerNote;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
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
    private final SettingGroup sgDisplay = settings.createGroup("Display");

    private final PlayerNote.Store store = new PlayerNote.Store();

    private final Setting<List<String>> stored = sgGeneral.add(new StringListSetting.Builder()
        .name("notes")
        .description("Saved notes, managed from the Notes screen. Editing this by hand is not recommended.")
        .defaultValue(List.of())
        .wide()
        .build()
    );

    private final Setting<Integer> capacity = sgGeneral.add(new IntSetting.Builder()
        .name("max-notes")
        .description("How many player notes to keep. The oldest note is dropped past this many.")
        .defaultValue(PlayerNote.Store.DEFAULT_CAPACITY)
        .min(10)
        .sliderRange(10, 5000)
        .onChanged(store::setCapacity)
        .build()
    );

    private final Setting<Boolean> feedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a chat note when a note is saved or removed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showActivity = sgDisplay.add(new BoolSetting.Builder()
        .name("show-activity")
        .description("Show a player's observed activity alongside their note.")
        .defaultValue(true)
        .build()
    );

    public PlayerNotesModule() {
        super(LogClient.CATEGORY, "player-notes",
            "Keeps notes about players and links them to observed activity.");
    }

    @Override
    public void onActivate() {
        // Apply the cap before loading so a config value loaded after construction, or a changed
        // setting, is honoured on the very first activate rather than only when it changes.
        store.setCapacity(capacity.get());
        store.loadAll(stored.get());
    }

    /** Persists the current notes into the setting so they survive a restart. */
    private void persist() {
        stored.set(store.encodeAll());
    }

    public PlayerNote.Store notes() {
        return store;
    }

    public boolean showActivity() {
        return showActivity.get();
    }

    /** Adds or updates a note for {@code player}. */
    public PlayerNote note(String player, String text) {
        PlayerNote note = store.put(player, text, System.currentTimeMillis());
        if (note == null) {
            warning("A note needs a player name.");
            return null;
        }
        persist();
        if (feedback.get()) info("Note saved for %s.", com.logvex.addon.common.Messages.player(player));
        return note;
    }

    public boolean removeNote(String player) {
        boolean removed = store.remove(player);
        if (removed) {
            persist();
            if (feedback.get()) info("Note removed for %s.", com.logvex.addon.common.Messages.player(player));
        }
        return removed;
    }

    public void clearNotes() {
        store.clear();
        persist();
        info("All player notes cleared.");
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
        WButton clear = list.add(theme.button("Clear All Notes")).expandX().widget();
        clear.action = this::clearNotes;
        return list;
    }
}
