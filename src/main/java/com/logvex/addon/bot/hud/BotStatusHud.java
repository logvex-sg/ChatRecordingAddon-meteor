package com.logvex.addon.bot.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotEvent;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;
import com.logvex.addon.framework.systems.modules.Modules;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HUD showing the bot's state, the task it is on and how far it has got.
 *
 * <p>It reads the live {@link BotModule} each frame and shows "Bot disabled" when the bot is not
 * present or not enabled, so it never displays stale information from a previous world. A task with a
 * known quantity also draws a progress bar, using the same bar the rest of the addon uses.
 */
public class BotStatusHud extends HudElement {
    public static final HudElementInfo<BotStatusHud> INFO =
        new HudElementInfo<>(LogClient.HUD_GROUP, "bot-status", "Autonomous bot status.", BotStatusHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showTask = sgGeneral.add(new BoolSetting.Builder()
        .name("show-task").description("Show the task the bot is working on.").defaultValue(true).build());
    private final Setting<Boolean> showProgress = sgGeneral.add(new BoolSetting.Builder()
        .name("show-progress").description("Show a progress bar for the active task.").defaultValue(true).build());
    private final Setting<Boolean> showQueue = sgGeneral.add(new BoolSetting.Builder()
        .name("show-queue").description("Show how many tasks are waiting.").defaultValue(true).build());
    private final Setting<Boolean> showEvents = sgGeneral.add(new BoolSetting.Builder()
        .name("show-events").description("Show the most recent bot event.").defaultValue(false).build());
    private final Setting<Boolean> showObjectives = sgGeneral.add(new BoolSetting.Builder()
        .name("show-objectives").description("Show how many objectives are queued.").defaultValue(true).build());
    private final Setting<Boolean> showNavigation = sgGeneral.add(new BoolSetting.Builder()
        .name("show-navigation").description("Show which navigation backend is driving the bot.")
        .defaultValue(false).build());

    private static final double BAR_HEIGHT = 4;

    public BotStatusHud() {
        super(INFO);
    }

    private static BotStatusHud instance;

    /** Adds the element to the HUD if it is not already present. */
    public static void enable() {
        var hud = com.logvex.addon.framework.systems.hud.Hud.get();
        hud.add(INFO, 4, 260);
        instance = (BotStatusHud) hud.instance(INFO);
    }

    /** Removes the element from the HUD. */
    public static void disable() {
        var hud = com.logvex.addon.framework.systems.hud.Hud.get();
        hud.remove(hud.instance(INFO));
        instance = null;
    }

    private BotModule module() {
        return Modules.get().get(BotModule.class);
    }

    private List<String> lines(BotModule module) {
        List<String> out = new ArrayList<>();
        if (module == null || !module.isActive()) return out;

        out.add("State: " + module.controller().state());
        BotTask task = module.controller().activeTask();
        if (showTask.get()) out.add(task == null ? "Task: idle" : "Task: " + task.describe());
        if (showQueue.get()) out.add("Queued: " + module.controller().queue().size());
        if (showObjectives.get()) out.add("Objectives: " + module.controller().goals().pendingCount());
        if (showEvents.get()) {
            List<BotEvent> events = module.controller().events();
            out.add(events.isEmpty() ? "No recent events" : events.get(0).message());
        }
        if (showNavigation.get()) out.add("Nav: " + module.navigation().backendName());
        return out;
    }

    private Color colorFor(BotModule module, HudStyle style) {
        if (module == null) return style.muted();
        return switch (module.controller().state()) {
            case FAILED -> style.bad();
            case COMPLETED -> style.good();
            case PAUSED, RECOVERING -> style.warn();
            default -> style.text();
        };
    }

    private boolean barVisible(BotModule module) {
        if (module == null || !module.isActive() || !showProgress.get()) return false;
        BotTask task = module.controller().activeTask();
        return task != null && task.required() > 0;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        List<String> lines = lines(module());
        double width = renderer.textWidth("Auto Bot", shadow, scale);
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, shadow, scale));
        width = Math.max(width, 90);

        double lineHeight = renderer.textHeight(shadow, scale) + HudPanel.LINE_GAP;
        double height = lineHeight * (Math.max(1, lines.size()) + 1) + HudPanel.PAD * 2;
        if (barVisible(module())) height += BAR_HEIGHT + 3;
        setSize(width + HudPanel.PAD * 2, height);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        BotModule module = module();
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Auto Bot", scale, shadow);

        List<String> lines = lines(module);
        if (lines.isEmpty()) {
            HudPanel.line(renderer, this, "Bot disabled", cursor, style.muted(), scale, shadow);
            return;
        }
        Color color = colorFor(module, style);
        for (String line : lines) {
            HudPanel.line(renderer, this, line, cursor, color, scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }

        if (barVisible(module)) {
            BotTask task = module.controller().activeTask();
            double barWidth = getWidth() - HudPanel.PAD * 2;
            renderer.bar(x + HudPanel.PAD, cursor + 1, barWidth, BAR_HEIGHT, task.fraction(), style.accent(), style.track());
        }
    }
}
