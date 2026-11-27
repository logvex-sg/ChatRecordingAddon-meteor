package com.logvex.addon.bot.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotEvent;
import com.logvex.addon.bot.core.BotTask;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

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
    private final Setting<Boolean> showSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("show-survival").description("Show health, food and whether a hostile mob is near.")
        .defaultValue(true).build());
    private final Setting<Boolean> showThreat = sgGeneral.add(new BoolSetting.Builder()
        .name("show-threat").description("Show a warning line when a hostile mob is within the threat radius.")
        .defaultValue(true).build());

    private static final double BAR_HEIGHT = 4;

    public BotStatusHud() {
        super(INFO);
    }

    private static BotStatusHud instance;

    /** Set by {@link #lines} when the threat line is present, so it can be drawn in the warning colour. */
    private String threatLine;

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
        threatLine = null;
        if (module == null || !module.isActive()) return out;

        out.add("State: " + module.controller().state());
        BotTask task = module.controller().activeTask();
        if (showTask.get()) out.add(task == null ? "Task: idle" : "Task: " + task.describe());
        if (showQueue.get()) out.add("Queued: " + module.controller().queue().size());
        if (showObjectives.get()) out.add("Objectives: " + module.controller().goals().pendingCount());
        if (showSurvival.get()) out.addAll(survivalLines(module));
        if (showEvents.get()) {
            List<BotEvent> events = module.controller().events();
            out.add(events.isEmpty() ? "No recent events" : events.get(0).message());
        }
        if (showNavigation.get()) {
            String nav = "Nav: " + module.navigation().backendName();
            int left = module.navigation().remainingWaypoints();
            // A planned route is several waypoints long, so showing the count makes a long detour
            // read as progress rather than as a stuck bot.
            if (left > 0) nav += " (" + left + " left)";
            out.add(nav);
        }
        return out;
    }

    /**
     * Health and food as compact bars, plus the threat warning.
     *
     * <p>Bars rather than bare numbers: the HUD is read at a glance in the middle of a fight, and
     * "7/20" takes longer to judge than a bar that is visibly low.
     */
    private List<String> survivalLines(BotModule module) {
        List<String> out = new ArrayList<>();
        var player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (player == null) return out;

        float maxHealth = Math.max(1f, player.getMaxHealth());
        out.add("Health: " + com.logvex.addon.assistant.core.ReplyFormat.bar(player.getHealth() / maxHealth, 10)
            + String.format(Locale.ROOT, " %.0f", player.getHealth()));
        int food = player.getHungerManager().getFoodLevel();
        out.add("Food: " + com.logvex.addon.assistant.core.ReplyFormat.bar(food / 20.0, 10) + " " + food);

        if (showThreat.get() && module.threatNearby(module.controller().config().threatRadius())) {
            threatLine = "Threat: hostile nearby";
            out.add(threatLine);
        }
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
            // The threat line is the one line that must not blend in with the rest.
            Color lineColor = line.equals(threatLine) ? style.bad() : color;
            HudPanel.line(renderer, this, line, cursor, lineColor, scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }

        if (barVisible(module)) {
            BotTask task = module.controller().activeTask();
            double barWidth = getWidth() - HudPanel.PAD * 2;
            renderer.bar(x + HudPanel.PAD, cursor + 1, barWidth, BAR_HEIGHT, task.fraction(), style.accent(), style.track());
        }
    }
}
