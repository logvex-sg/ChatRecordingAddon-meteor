package com.logvex.addon.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.modules.Category;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;

/**
 * The addon's main window.
 *
 * <p>Lists every module grouped by category with a search box, a toggle per module and a button that
 * opens that module's settings, then the HUD elements below. This is the entry point that makes the
 * modules reachable without a command; it opens on Right Shift and from {@code .log gui}.
 */
public class ClickGuiScreen extends WidgetScreen {
    private String query = "";

    public ClickGuiScreen() {
        super(GuiThemes.get(), "Log Addon");
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        WTextBox search = add(theme.textBox(query, "Search modules...")).expandX().widget();
        search.action = () -> {
            query = search.get();
            reload();
        };

        String needle = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Category category : Modules.get().categories()) {
            List<Module> group = Modules.get().getGroup(category).stream()
                .filter(module -> matches(module, needle))
                .toList();
            if (group.isEmpty()) continue;

            add(theme.horizontalSeparator(category.name + " (" + group.size() + ")")).expandX();
            for (Module module : group) {
                add(moduleRow(theme, module)).expandX();
                shown++;
            }
        }

        if (shown == 0) {
            add(theme.label("No modules match \"" + query + "\".").color(theme.muted)).expandX();
        }

        add(theme.horizontalSeparator("HUD")).expandX();
        int hudCount = 0;
        for (HudElementInfo<?> info : Hud.get().infos()) {
            add(hudRow(theme, info)).expandX();
            hudCount++;
        }
        if (hudCount == 0) {
            add(theme.label("No HUD elements are registered.").color(theme.muted)).expandX();
        }

        add(theme.button("Close", () -> MinecraftClient.getInstance().setScreen(parent))).expandX();
    }

    private WTable moduleRow(GuiTheme theme, Module module) {
        WTable row = theme.table();

        WButton toggle = row.add(theme.button(module.isActive() ? "On" : "Off")).widget();
        toggle.action = () -> {
            module.toggle();
            reload();
        };

        WVerticalList details = theme.verticalList();
        details.add(theme.label(module.title)).expandX();
        if (module.description != null && !module.description.isBlank()) {
            details.add(theme.label(Messages.truncate(module.description, 64)).color(theme.muted)).expandX();
        }
        row.add(details).expandCellX();

        WButton settings = row.add(theme.button("Settings")).widget();
        settings.action = () -> MinecraftClient.getInstance().setScreen(new ModuleSettingsScreen(module));

        row.finish();
        return row;
    }

    private WTable hudRow(GuiTheme theme, HudElementInfo<?> info) {
        WTable row = theme.table();

        WButton toggle = row.add(theme.button(Hud.get().isActive(info) ? "On" : "Off")).widget();
        toggle.action = () -> {
            Hud.get().toggle(info);
            reload();
        };

        WVerticalList details = theme.verticalList();
        details.add(theme.label(info.title)).expandX();
        if (info.description != null && !info.description.isBlank()) {
            details.add(theme.label(Messages.truncate(info.description, 64)).color(theme.muted)).expandX();
        }
        row.add(details).expandCellX();

        WButton settings = row.add(theme.button("Settings")).widget();
        settings.action = () -> MinecraftClient.getInstance().setScreen(new HudElementSettingsScreen(info));

        row.finish();
        return row;
    }

    private boolean matches(Module module, String needle) {
        if (needle.isEmpty()) return true;
        if (module.title.toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (module.name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return module.description != null && module.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x66000000);
        super.render(context, mouseX, mouseY, delta);
    }
}
