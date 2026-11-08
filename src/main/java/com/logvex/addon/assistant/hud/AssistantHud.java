package com.logvex.addon.assistant.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.assistant.modules.AssistantModule;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.hud.HudPanel;
import com.logvex.addon.framework.systems.hud.HudRenderer;
import com.logvex.addon.framework.systems.hud.HudStyle;
import com.logvex.addon.framework.systems.modules.Modules;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD showing the assistant's state: how many triggers are loaded, how many facts are remembered and
 * how many messages it has handled this session.
 *
 * <p>It reads the live {@link AssistantModule} each frame and shows a single "Disabled" line when the
 * assistant is off, so it never displays stale information from a previous world.
 */
public class AssistantHud extends HudElement {
    public static final HudElementInfo<AssistantHud> INFO =
        new HudElementInfo<>(LogClient.HUD_GROUP, "assistant", "Assistant status.", AssistantHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showMemory = sgGeneral.add(new BoolSetting.Builder()
        .name("show-memory").description("Show how many facts are remembered.").defaultValue(true).build());
    private final Setting<Boolean> showTriggers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-triggers").description("Show how many triggers are loaded.").defaultValue(true).build());
    private final Setting<Boolean> showHandled = sgGeneral.add(new BoolSetting.Builder()
        .name("show-handled").description("Show how many messages the assistant handled.").defaultValue(true).build());
    private final Setting<Boolean> showAi = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ai").description("Show the local AI's state and how many messages it answered.")
        .defaultValue(false).build());

    public AssistantHud() {
        super(INFO);
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
        if (showAi.get()) {
            var engine = module.aiEngine();
            out.add("AI: " + (engine.enabled() ? engine.understood() + " understood" : "off"));
        }
        return out;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudPanel.measure(this, renderer, scale, shadow, "Assistant", lines(module()));
    }

    @Override
    public void render(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Assistant", scale, shadow);

        List<String> lines = lines(module());
        if (lines.isEmpty()) {
            HudPanel.line(renderer, this, "Disabled", cursor, style.muted(), scale, shadow);
            return;
        }
        for (String line : lines) {
            HudPanel.line(renderer, this, line, cursor, style.text(), scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
