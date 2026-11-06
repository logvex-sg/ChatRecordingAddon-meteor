package com.logvex.addon.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;

/** Settings for one HUD element, including whether it is currently shown. */
public class HudElementSettingsScreen extends WidgetScreen {
    private final HudElementInfo<?> info;
    private final HudElement element;
    private String query = "";

    public HudElementSettingsScreen(HudElementInfo<?> info) {
        super(GuiThemes.get(), info.title);
        this.info = info;
        this.element = Hud.get().instance(info);
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        WTextBox search = add(theme.textBox(query, "Search settings...")).expandX().widget();
        search.action = () -> {
            query = search.get();
            reload();
        };

        add(theme.button("Back", () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new ClickGuiScreen()))).expandX();
        add(theme.button(Hud.get().isActive(info) ? "Hide element" : "Show element", () -> {
            Hud.get().toggle(info);
            reload();
        })).expandX();

        if (element == null) return;

        String needle = query.trim().toLowerCase(Locale.ROOT);
        boolean any = false;
        for (SettingGroup group : element.settings.groups()) {
            List<Setting<?>> matching = group.settings().stream()
                .filter(setting -> matches(setting, needle))
                .toList();
            if (matching.isEmpty()) continue;
            any = true;
            add(theme.horizontalSeparator(group.name())).expandX();
            for (WWidget row : SettingsWidgets.rows(theme, matching)) {
                add(row).expandX();
            }
        }
        if (!any && needle.isEmpty()) {
            add(theme.label("This element has no settings.").color(theme.muted)).expandX();
        }
    }

    private boolean matches(Setting<?> setting, String needle) {
        if (needle.isEmpty()) return true;
        if (setting.name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return setting.description != null && setting.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        root.maxHeight = Math.max(40, this.height - 20);
        super.render(context, mouseX, mouseY, delta);
    }
}
