package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.SessionRecord;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.chatlog.mc.LogService;

import java.util.List;

/**
 * A reviewable history of recording sessions.
 *
 * <p>Each session that ends is folded into a compact record (server, duration, message and event
 * counts) so past sessions can be browsed without re-reading the log archive. The history is
 * bounded, and the cap is configurable.
 */
public class SessionHistoryModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final LogService service = LogService.get();

    private final Setting<Integer> capacity = sgGeneral.add(new IntSetting.Builder()
        .name("history-size")
        .description("How many past sessions to keep. Older sessions are dropped first.")
        .defaultValue(500)
        .min(10)
        .sliderRange(10, 5000)
        .onChanged(value -> service.sessionHistory().setCapacity(value))
        .build()
    );

    public SessionHistoryModule() {
        super(LogAddon.CATEGORY, "session-history",
            "Keeps a browsable history of past recording sessions.");
    }

    @Override
    public void onActivate() {
        service.sessionHistory().setCapacity(capacity.get());
    }

    /** Sessions matching {@code filter}, newest first. */
    public List<SessionRecord> sessions(String filter) {
        return service.sessionHistory().search(filter);
    }

    public int size() {
        return service.sessionHistory().size();
    }

    public long totalMessages() {
        return service.sessionHistory().totalMessages();
    }

    public long totalDurationMs() {
        return service.sessionHistory().totalDurationMs(System.currentTimeMillis());
    }

    public SessionRecord latest() {
        return service.sessionHistory().latest();
    }

    public void clear() {
        service.sessionHistory().clear();
        info("Session history cleared.");
    }

    @Override
    public String getInfoString() {
        return size() + " sessions";
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Session History")).expandX().widget();
        open.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.SessionHistoryScreen(this));

        WButton clear = list.add(theme.button("Clear History")).expandX().widget();
        clear.action = this::clear;
        return list;
    }
}
