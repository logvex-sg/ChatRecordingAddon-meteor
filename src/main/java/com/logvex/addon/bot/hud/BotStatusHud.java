package com.logvex.addon.bot.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotEvent;
import com.logvex.addon.bot.core.BotTask;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD showing the bot's state, the task it is on and how far it has got.
 *
 * <p>It reads the live {@link BotModule} each frame and shows nothing when the bot is not present or
 * not enabled, so it never displays stale information from a previous world.
 */
public class BotStatusHud extends HudElement {
    public static final HudElementInfo<BotStatusHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "bot-status", "Autonomous bot status.", BotStatusHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);
    private static final Color SUCCESS = new Color(120, 220, 130);
    private static final Color WARNING = new Color(240, 200, 110);
    private static final Color ERROR = new Color(240, 120, 120);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

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

    private final Setting<Boolean> background = sgStyle.add(new BoolSetting.Builder()
        .name("background").description("Draw a background behind the HUD.").defaultValue(true).build());
    private final Setting<SettingColor> backgroundColor = sgStyle.add(new ColorSetting.Builder()
        .name("background-color").description("Background color.")
        .defaultValue(new SettingColor(12, 12, 16, 170)).visible(background::get).build());
    private final Setting<SettingColor> titleColor = sgStyle.add(new ColorSetting.Builder()
        .name("title-color").description("Title color.")
        .defaultValue(new SettingColor(140, 200, 255)).build());
    private final Setting<Boolean> customScale = sgStyle.add(new BoolSetting.Builder()
        .name("custom-scale").description("Use a custom text scale instead of the global one.")
        .defaultValue(false).build());
    private final Setting<Double> scale = sgStyle.add(new DoubleSetting.Builder()
        .name("scale").description("Custom scale.").defaultValue(1).min(0.5).sliderRange(0.5, 3)
        .visible(customScale::get).build());

    public BotStatusHud() {
        super(INFO);
    }

    private static BotStatusHud instance;

    /** Adds the element to the HUD if it is not already present. */
    public static void enable() {
        var hud = meteordevelopment.meteorclient.systems.hud.Hud.get();
        BotStatusHud existing = find(hud);
        if (existing != null) {
            if (!existing.isActive()) existing.toggle();
            instance = existing;
            return;
        }
        hud.add(INFO, 4, 260);
        instance = find(hud);
    }

    /** Removes the element from the HUD. */
    public static void disable() {
        if (instance != null) {
            instance.remove();
            instance = null;
            return;
        }
        var hud = meteordevelopment.meteorclient.systems.hud.Hud.get();
        BotStatusHud existing = find(hud);
        if (existing != null) existing.remove();
    }

    private static BotStatusHud find(meteordevelopment.meteorclient.systems.hud.Hud hud) {
        for (var element : hud) {
            if (element instanceof BotStatusHud found) return found;
        }
        return null;
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private BotModule module() {
        return Modules.get().get(BotModule.class);
    }

    private List<String> lines(BotModule module) {
        List<String> out = new ArrayList<>();
        if (module == null || !module.isActive()) return out;

        out.add("State: " + module.controller().state());
        BotTask task = module.controller().activeTask();
        if (showTask.get()) {
            out.add(task == null ? "Task: idle" : "Task: " + task.describe());
        }
        if (showProgress.get() && task != null && task.required() > 0) {
            out.add(String.format(java.util.Locale.ROOT, "Progress: %d/%d (%.0f%%)",
                task.progress(), task.required(), task.fraction() * 100));
        }
        if (showQueue.get()) {
            out.add("Queued: " + module.controller().queue().size());
        }
        if (showObjectives.get()) {
            out.add("Objectives: " + module.controller().goals().pendingCount());
        }
        if (showEvents.get()) {
            List<BotEvent> events = module.controller().events();
            out.add(events.isEmpty() ? "No recent events" : events.get(0).message());
        }
        if (showNavigation.get()) {
            out.add("Nav: " + module.navigation().backendName());
        }
        return out;
    }

    private Color colorFor(BotModule module) {
        if (module == null) return MUTED;
        return switch (module.controller().state()) {
            case FAILED -> ERROR;
            case COMPLETED -> SUCCESS;
            case PAUSED, RECOVERING -> WARNING;
            default -> TEXT;
        };
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        List<String> lines = lines(module());

        double width = renderer.textWidth("Auto Bot", true, scaleValue);
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, true, scaleValue));

        double height = lineHeight * (1 + Math.max(1, lines.size()));
        setSize(width + 6, height + 6);
    }

    @Override
    public void render(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        double left = x + 3;
        double cursor = y + 3;

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        renderer.text("Auto Bot", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        BotModule module = module();
        List<String> lines = lines(module);
        if (lines.isEmpty()) {
            renderer.text("Bot disabled", left, cursor, MUTED, true, scaleValue);
            return;
        }
        Color color = colorFor(module);
        for (String line : lines) {
            renderer.text(line, left, cursor, color, true, scaleValue);
            cursor += lineHeight;
        }
    }
}
