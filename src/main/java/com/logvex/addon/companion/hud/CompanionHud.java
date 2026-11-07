package com.logvex.addon.companion.hud;

import com.logvex.addon.LogClient;
import com.logvex.addon.common.Messages;
import com.logvex.addon.companion.mc.CompanionModule;
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
 * HUD element showing whether the companion is listening and how many app clients are attached.
 *
 * <p>It reads the module's live state, so it shows "off" the moment the module is disabled and never
 * keeps a stale port after a restart. It renders nothing when the module is not installed, so it can
 * never show a misleading "off" for a feature that does not exist.
 */
public class CompanionHud extends HudElement {
    public static final HudElementInfo<CompanionHud> INFO =
        new HudElementInfo<>(LogClient.HUD_GROUP, "companion", "Companion web server status.",
            CompanionHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showPort = sgGeneral.add(new BoolSetting.Builder()
        .name("show-port").description("Show the listening port.").defaultValue(true).build());
    private final Setting<Boolean> showClients = sgGeneral.add(new BoolSetting.Builder()
        .name("show-clients").description("Show how many app clients are connected.").defaultValue(true).build());
    private final Setting<Boolean> hideWhenOff = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-when-off").description("Hide the element entirely while the companion is off.")
        .defaultValue(true).build());

    public CompanionHud() {
        super(INFO);
    }

    private CompanionModule module() {
        return Modules.get().get(CompanionModule.class);
    }

    private record Row(String key, String value) {
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        CompanionModule module = module();
        if (module == null) return out;
        if (module.isRunning()) {
            out.add(new Row("Status", "listening"));
            if (showPort.get()) out.add(new Row("Port", String.valueOf(module.boundPort())));
            if (showClients.get()) out.add(new Row("Clients", Messages.number(module.connectedClients())));
        } else {
            out.add(new Row("Status", "off"));
        }
        return out;
    }

    @Override
    public void tick(HudRenderer renderer) {
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        CompanionModule module = module();
        if (module == null || (hideWhenOff.get() && !module.isRunning())) {
            setSize(0, 0);
            return;
        }
        List<Row> rows = rows();
        double width = renderer.textWidth("Companion", shadow, scale);
        for (Row row : rows) {
            double rowWidth = renderer.textWidth(row.key(), shadow, scale)
                + renderer.textWidth(row.value(), shadow, scale) + 10;
            width = Math.max(width, rowWidth);
        }
        double lineHeight = renderer.textHeight(shadow, scale) + HudPanel.LINE_GAP;
        int count = Math.max(1, rows.size());
        setSize(width + HudPanel.PAD * 2, lineHeight * (count + 1) + HudPanel.PAD * 2);
    }

    @Override
    public void render(HudRenderer renderer) {
        CompanionModule module = module();
        if (module == null || (hideWhenOff.get() && !module.isRunning())) return;
        double scale = HudPanel.scale(shared);
        boolean shadow = shared.shadow.get();
        HudStyle style = HudPanel.style(shared, renderer.theme);
        double cursor = HudPanel.frame(renderer, this, style, shared.background.get(), shared.border.get(),
            "Companion", scale, shadow);

        List<Row> rows = rows();
        if (rows.isEmpty()) {
            HudPanel.line(renderer, this, "Not running", cursor, style.muted(), scale, shadow);
            return;
        }
        for (Row row : rows) {
            HudPanel.keyValue(renderer, this, row.key(), row.value(), cursor, style.muted(), style.text(),
                scale, shadow);
            cursor += HudPanel.lineHeight(renderer, scale);
        }
    }
}
