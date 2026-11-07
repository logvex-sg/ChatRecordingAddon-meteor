package com.logvex.addon.assistant.hud;

import com.logvex.addon.LogAddon;
import com.logvex.addon.assistant.modules.AssistantModule;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.ColorSetting;
import com.logvex.addon.framework.settings.DoubleSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.modules.Modules;
import com.logvex.addon.framework.utils.render.color.Color;
import com.logvex.addon.framework.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD showing the assistant's state: how many triggers are loaded, how many facts are remembered and
 * how many messages it has handled this session.
 *
 * <p>It reads the live {@link AssistantModule} each frame and shows a single "disabled" line when the
 * assistant is off, so it never displays stale information from a previous world.
 */
public class AssistantHud extends HudElement {
    public static final HudElementInfo<AssistantHud> INFO =
        new HudElementInfo<>(LogAddon.HUD_GROUP, "assistant", "Assistant status.", AssistantHud::new);

    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color TEXT = new Color(230, 230, 230);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStyle = settings.createGroup("Style");

    private final Setting<Boolean> showMemory = sgGeneral.add(new BoolSetting.Builder()
        .name("show-memory").description("Show how many facts are remembered.").defaultValue(true).build());
    private final Setting<Boolean> showTriggers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-triggers").description("Show how many triggers are loaded.").defaultValue(true).build());
    private final Setting<Boolean> showHandled = sgGeneral.add(new BoolSetting.Builder()
        .name("show-handled").description("Show how many messages the assistant handled.").defaultValue(true).build());

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

    public AssistantHud() {
        super(INFO);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : -1;
    }

    private AssistantModule module() {
        return Modules.get().get(AssistantModule.class);
    }

    private List<String> lines(AssistantModule module) {
        List<String> out = new ArrayList<>();
        if (module == null || !module.isActive()) return out;
        if (showTriggers.get()) out.add("Triggers: " + module.triggers().rules().size());
        if (showMemory.get()) out.add("Facts: " + module.memory().facts().size());
        if (showHandled.get()) out.add("Handled: " + module.runtime().handled());
        return out;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scaleValue = getScale();
        double lineHeight = renderer.textHeight(true, scaleValue) + 1;
        List<String> lines = lines(module());

        double width = renderer.textWidth("Assistant", true, scaleValue);
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

        renderer.text("Assistant", left, cursor, titleColor.get(), true, scaleValue);
        cursor += lineHeight;

        List<String> lines = lines(module());
        if (lines.isEmpty()) {
            renderer.text("Disabled", left, cursor, MUTED, true, scaleValue);
            return;
        }
        for (String line : lines) {
            renderer.text(line, left, cursor, TEXT, true, scaleValue);
            cursor += lineHeight;
        }
    }
}
