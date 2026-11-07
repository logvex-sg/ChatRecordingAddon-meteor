package com.logvex.addon.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WCard;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.gui.widgets.pressable.WToggle;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Locale;

/**
 * Settings for one module.
 *
 * <p>Settings are grouped exactly as the module declares them, and a search box filters by name so a
 * module with forty settings is still navigable. The module's own custom controls
 * ({@link Module#getWidget}) are shown first, above the generic settings, because that is where the
 * module's primary actions live.
 */
public class ModuleSettingsScreen extends WidgetScreen {
    private final Module module;
    private String query = "";

    public ModuleSettingsScreen(Module module) {
        this(module, null);
    }

    public ModuleSettingsScreen(Module module, Screen parent) {
        super(GuiThemes.get(), module.title);
        this.module = module;
        if (parent != null) this.parent = parent;
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        WHorizontalList header = theme.horizontalList();
        WButton back = header.add(theme.button("< Back")).widget();
        back.action = () -> MinecraftClient.getInstance().setScreen(
            parent instanceof ClickGuiScreen ? parent : new ClickGuiScreen());
        header.add(theme.label(module.title).color(theme.accent)).expandX();
        WToggle enabled = header.add(theme.toggle(module.isActive() ? "On" : "Off", module::isActive, value -> {
            module.toggle();
            ConfigManager.get().markDirty();
            reload();
        })).widget();
        enabled.theme = theme;
        add(header).expandX();

        if (module.description != null && !module.description.isBlank()) {
            add(theme.label(module.description).color(theme.muted)).expandX();
        }

        WTextBox search = add(theme.textBox(query, "Search settings...")).expandX().widget();
        search.action = () -> {
            query = search.get();
            reload();
        };

        WWidget custom = module.getWidget(theme);
        if (custom != null) {
            custom.theme = theme;
            add(custom).expandX();
        }

        String needle = query.trim().toLowerCase(Locale.ROOT);
        boolean any = false;
        for (SettingGroup group : module.settings.groups()) {
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

        if (!any && needle.isEmpty() && custom == null) {
            emptyState(theme, "This module has no settings.");
        } else if (!any && !needle.isEmpty()) {
            emptyState(theme, "No settings match \"" + query + "\".");
        }
    }

    /** A small card explaining that there is nothing to show, rather than a bare line of text. */
    private void emptyState(GuiTheme theme, String message) {
        WCard card = theme.card();
        WVerticalList column = theme.verticalList();
        column.add(theme.label(message).color(theme.muted)).expandX();
        card.add(column).expandX();
        add(card).expandX();
    }

    private boolean matches(Setting<?> setting, String needle) {
        if (needle.isEmpty()) return true;
        if (setting.name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return setting.description != null && setting.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, Render2D.argb(theme.screenBackground));
        root.maxHeight = Math.max(40, this.height - 20);
        super.render(context, mouseX, mouseY, delta);
    }
}
