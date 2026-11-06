package com.logvex.addon.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.modules.Module;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;

/**
 * Settings for one module.
 *
 * <p>Settings are grouped exactly as the module declares them, and a search box filters by name so
 * a module with forty settings is still navigable. The module's own custom controls
 * ({@link Module#getWidget}) are shown first, above the generic settings, because that is where the
 * module's primary actions live.
 */
public class ModuleSettingsScreen extends WidgetScreen {
    private final Module module;
    private String query = "";

    public ModuleSettingsScreen(Module module) {
        super(GuiThemes.get(), module.title);
        this.module = module;
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        WTextBox search = add(theme.textBox(query, "Search settings...")).expandX().widget();
        search.action = () -> {
            query = search.get();
            reload();
        };

        WButton back = add(theme.button("Back", () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new ClickGuiScreen()))).expandX().widget();
        back.action = () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new ClickGuiScreen());

        WWidget custom = module.getWidget(theme);
        if (custom != null) {
            custom.theme = theme;
            add(custom).expandX();
        }

        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (SettingGroup group : module.settings.groups()) {
            List<Setting<?>> matching = group.settings().stream()
                .filter(setting -> matches(setting, needle))
                .toList();
            if (matching.isEmpty()) continue;

            add(theme.horizontalSeparator(group.name())).expandX();
            for (WWidget row : SettingsWidgets.rows(theme, matching)) {
                add(row).expandX();
            }
        }

        if (needle.isEmpty() && module.settings.all().isEmpty() && custom == null) {
            add(theme.label("This module has no settings.").color(theme.muted)).expandX();
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
