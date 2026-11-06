package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogAddon;
import com.logvex.addon.chatlog.core.SessionTimeline;
import com.logvex.addon.chatlog.mc.SessionTimelineService;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;

import java.util.List;

/**
 * Chronological record of session milestones: joins, leaves, deaths and session start/end.
 *
 * <p>Only events the client can actually observe are recorded. The timeline itself is maintained by
 * {@link SessionTimelineService}, which runs regardless of this module, so milestones from before
 * the module was enabled are still present.
 */
public class SessionTimelineModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> recordJoins = sgGeneral.add(new BoolSetting.Builder()
        .name("record-joins")
        .description("Record player joins on the timeline.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> recordLeaves = sgGeneral.add(new BoolSetting.Builder()
        .name("record-leaves")
        .description("Record player leaves on the timeline.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> recordDeaths = sgGeneral.add(new BoolSetting.Builder()
        .name("record-deaths")
        .description("Record deaths on the timeline.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxEvents = sgGeneral.add(new IntSetting.Builder()
        .name("max-events")
        .description("How many milestones the timeline keeps before dropping the oldest.")
        .defaultValue(500)
        .min(50)
        .sliderRange(50, 2000)
        .build()
    );

    private final SessionTimelineService service = SessionTimelineService.get();

    public SessionTimelineModule() {
        super(LogAddon.CATEGORY, "session-timeline",
            "Chronological record of joins, leaves, deaths and session events.");
    }

    @Override
    public void onActivate() {
        service.setRecording(true);
        info("Timeline recording started.");
    }

    @Override
    public void onDeactivate() {
        service.setRecording(false);
        info("Timeline recording stopped. %s kept.",
            com.logvex.addon.common.Messages.plural(service.timeline().size(), "milestone"));
    }

    public SessionTimeline timeline() {
        return service.timeline();
    }

    /** Events matching {@code filter}, newest first, honouring the per-kind toggles. */
    public List<SessionTimeline.Event> events(String filter) {
        List<SessionTimeline.Event> out = new java.util.ArrayList<>();
        for (SessionTimeline.Event event : service.timeline().search(filter)) {
            switch (event.kind()) {
                case JOIN -> {
                    if (recordJoins.get()) out.add(event);
                }
                case LEAVE -> {
                    if (recordLeaves.get()) out.add(event);
                }
                case DEATH -> {
                    if (recordDeaths.get()) out.add(event);
                }
                default -> out.add(event);
            }
        }
        return out;
    }

    @Override
    public String getInfoString() {
        return service.timeline().size() + " events";
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Open Timeline")).expandX().widget();
        open.action = () -> mc.setScreen(new com.logvex.addon.chatlog.gui.TimelineScreen(this));
        WButton clear = list.add(theme.button("Clear Timeline")).expandX().widget();
        clear.action = () -> {
            service.timeline().clear();
            info("Timeline cleared.");
        };
        return list;
    }
}
