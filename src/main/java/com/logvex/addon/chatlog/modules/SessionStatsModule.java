package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.SessionSummary;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;

/**
 * Session statistics: message counts by type, unique players, join/leave/death totals and keyword
 * alert counts. All numbers come from the shared {@link com.logvex.addon.chatlog.core.StatsCollector}
 * and reflect only what the client actually observed.
 *
 * <p>It also prints a short session summary when the client leaves a world, so the session's shape
 * is visible without opening a screen.
 */
public class SessionStatsModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> summaryOnLeave = sgGeneral.add(new BoolSetting.Builder()
        .name("summary-on-leave")
        .description("Print a short session summary when leaving a world.")
        .defaultValue(true)
        .build()
    );

    private final LogService service = LogService.get();

    public SessionStatsModule() {
        super(LogClient.CATEGORY, "session-stats", "Collects session statistics for the stats HUD and dashboard.");
    }

    public StatsSnapshot snapshot() {
        return service.snapshot();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!summaryOnLeave.get()) return;
        StatsSnapshot snapshot = service.snapshot();
        if (SessionSummary.isEmpty(snapshot)) return;
        for (String line : SessionSummary.lines(snapshot)) info("%s", line);
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
        WButton now = list.add(theme.button("Show Summary Now")).expandX().widget();
        now.action = () -> {
            StatsSnapshot snapshot = service.snapshot();
            if (SessionSummary.isEmpty(snapshot)) {
                info("No session statistics to summarise yet.");
                return;
            }
            for (String line : SessionSummary.lines(snapshot)) info("%s", line);
        };
        return list;
    }
}
