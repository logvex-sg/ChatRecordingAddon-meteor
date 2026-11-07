package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.PlayerActivity;
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
import com.logvex.addon.framework.systems.modules.Modules;

import java.util.List;

/**
 * Per-player activity overview: joins, leaves, deaths and message counts, rebuilt from the stored
 * logs on demand. Only events a client can observe are counted.
 */
public class PlayerActivityModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> rebuildOnActivate = sgGeneral.add(new BoolSetting.Builder()
        .name("rebuild-on-activate")
        .description("Rebuild the player index from stored logs when the module is enabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minMessages = sgGeneral.add(new IntSetting.Builder()
        .name("min-messages")
        .description("Hide players below this message count in the overview.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<List<String>> excluded = sgGeneral.add(new StringListSetting.Builder()
        .name("excluded-players")
        .description("Players never tracked or listed (one name per line).")
        .defaultValue()
        .onChanged(value -> {
            LogService.get().players().setExcluded(value);
            LogService.get().sessions().setExcluded(value);
        })
        .build()
    );

    private final LogService service = LogService.get();

    public PlayerActivityModule() {
        super(LogClient.CATEGORY, "player-activity", "Tracks and displays per-player chat activity.");
    }

    @Override
    public void onActivate() {
        service.players().setExcluded(excluded.get());
        service.sessions().setExcluded(excluded.get());
        if (!rebuildOnActivate.get()) return;
        ChatLoggerModule logger = Modules.get().get(ChatLoggerModule.class);
        if (logger == null || logger.storage() == null) return;
        logger.storage().rebuildPlayersAsync(service.players(), service.sessions(),
            () -> info("Player activity index rebuilt."));
    }

    /** Per-player session durations, most recently seen first. */
    public List<com.logvex.addon.chatlog.core.PlayerSession> sessions(String filter) {
        return service.sessions().search(filter);
    }

    /** Players matching the filter, honouring the minimum-message setting. */
    public List<PlayerActivity> players(String filter) {
        int min = minMessages.get();
        return service.players().search(filter).stream()
            .filter(activity -> activity.messageCount() >= min)
            .toList();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Player Activity")).expandX().widget();
        open.action = () -> {
            ChatLoggerModule logger = Modules.get().get(ChatLoggerModule.class);
            if (logger != null) mc.setScreen(new com.logvex.addon.chatlog.gui.LogDashboardScreen(logger, 2));
        };
        WButton rebuild = list.add(theme.button("Rebuild Index")).expandX().widget();
        rebuild.action = () -> {
            ChatLoggerModule logger = Modules.get().get(ChatLoggerModule.class);
            if (logger != null && logger.storage() != null) {
                logger.storage().rebuildPlayersAsync(service.players(), () -> info("Player activity index rebuilt."));
            }
        };
        return list;
    }
}
