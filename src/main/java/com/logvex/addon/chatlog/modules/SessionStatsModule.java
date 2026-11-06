package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;

/**
 * Session statistics: message counts by type, unique players, join/leave/death totals and keyword
 * alert counts. All numbers come from the shared {@link com.logvex.addon.chatlog.core.StatsCollector}
 * and reflect only what the client actually observed.
 */
public class SessionStatsModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> resetOnJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("reset-on-join")
        .description("Reset the session counters when joining a server.")
        .defaultValue(true)
        .build()
    );

    private final LogService service = LogService.get();

    public SessionStatsModule() {
        super(LogAddon.CATEGORY, "session-stats", "Collects session statistics for the stats HUD and dashboard.");
    }

    public StatsSnapshot snapshot() {
        return service.snapshot();
    }

    public boolean resetOnJoin() {
        return resetOnJoin.get();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Statistics")).expandX().widget();
        open.action = () -> {
            ChatLoggerModule logger = com.logvex.addon.framework.systems.modules.Modules.get()
                .get(ChatLoggerModule.class);
            if (logger != null) mc.setScreen(new com.logvex.addon.chatlog.gui.LogDashboardScreen(logger, 4));
        };
        return list;
    }
}
