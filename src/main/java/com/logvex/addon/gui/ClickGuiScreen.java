package com.logvex.addon.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.Addon;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemePreset;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.systems.config.ClientConfig;
import com.logvex.addon.framework.systems.config.ConfigManager;
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
 * <p>One entry point that makes every feature reachable without a command: a searchable module list
 * grouped by category, the HUD element list, a colour-scheme picker and an About page. It opens on
 * Right Shift and from {@code .log gui}.
 *
 * <p>The window is tabbed rather than one long scroll so the important lists stay short and the
 * screen keeps a stable shape as modules are added. Search filters within the active tab, matching a
 * module's title, id or description, so a user who knows roughly what they want can type three
 * letters and find it.
 */
public class ClickGuiScreen extends WidgetScreen {
    private enum Tab {
        MODULES("Modules"),
        HUD("HUD"),
        THEME("Theme"),
        ABOUT("About");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private Tab tab = Tab.MODULES;
    private String query = "";

    public ClickGuiScreen() {
        super(GuiThemes.get(), "Log Addon");
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        // Header: the addon name and version, so the window is self-identifying.
        WHorizontalList header = add(theme.horizontalList()).expandX().widget();
        header.add(theme.label(Addon.NAME + "  v" + Addon.VERSION)).expandX();
        header.add(theme.label(activeSummary())).widget().color(theme.muted);

        WTable tabs = add(theme.table()).expandX().widget();
        for (Tab value : Tab.values()) {
            WButton button = tabs.add(theme.button(value == tab ? "[" + value.label + "]" : value.label)).widget();
            button.action = () -> {
                tab = value;
                reload();
            };
        }
        tabs.finish();

        // Search only makes sense where there is a long list to filter.
        if (tab == Tab.MODULES || tab == Tab.HUD) {
            WTextBox search = add(theme.textBox(query,
                tab == Tab.MODULES ? "Search modules..." : "Search HUD elements...")).expandX().widget();
            search.action = () -> {
                query = search.get();
                reload();
            };
        }

        add(theme.horizontalSeparator()).expandX();

        switch (tab) {
            case MODULES -> renderModules(theme);
            case HUD -> renderHud(theme);
            case THEME -> renderTheme(theme);
            case ABOUT -> renderAbout(theme);
        }

        add(theme.button("Close", () -> MinecraftClient.getInstance().setScreen(parent))).expandX();
    }

    // ------------------------------------------------------------------ modules

    private void renderModules(GuiTheme theme) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Category category : Modules.get().categories()) {
            List<Module> group = Modules.get().getGroup(category).stream()
                .filter(module -> matches(module, needle))
                .toList();
            if (group.isEmpty()) continue;

            long active = group.stream().filter(Module::isActive).count();
            add(theme.horizontalSeparator(category.name + "  (" + active + "/" + group.size() + " on)")).expandX();
            for (Module module : group) {
                add(moduleRow(theme, module)).expandX();
                shown++;
            }
        }

        if (shown == 0) {
            add(theme.label(Modules.get().getAll().isEmpty()
                ? "No modules are registered."
                : "No modules match \"" + query + "\".").color(theme.muted)).expandX();
        }
    }

    private WTable moduleRow(GuiTheme theme, Module module) {
        WTable row = theme.table();

        WButton toggle = row.add(theme.button(module.isActive() ? "On" : "Off")).widget();
        toggle.action = () -> {
            module.toggle();
            ConfigManager.get().markDirty();
            reload();
        };

        WVerticalList details = theme.verticalList();
        details.add(theme.label(module.title)).expandX();
        if (module.description != null && !module.description.isBlank()) {
            details.add(theme.label(Messages.truncate(module.description, 72)).color(theme.muted)).expandX();
        }
        row.add(details).expandCellX();

        WButton settings = row.add(theme.button("Settings")).widget();
        settings.action = () -> MinecraftClient.getInstance().setScreen(new ModuleSettingsScreen(module));

        row.finish();
        return row;
    }

    // ------------------------------------------------------------------ hud

    private void renderHud(GuiTheme theme) {
        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton editor = actions.add(theme.button("Open HUD Editor")).expandX().widget();
        editor.action = () -> MinecraftClient.getInstance().setScreen(new HudEditorScreen());
        WButton hideAll = actions.add(theme.button("Hide All")).expandX().widget();
        hideAll.action = () -> {
            for (HudElementInfo<?> info : Hud.get().infos()) {
                if (Hud.get().isActive(info)) Hud.get().remove(Hud.get().instance(info));
            }
            ConfigManager.get().markDirty();
            reload();
        };

        String needle = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (HudElementInfo<?> info : Hud.get().infos()) {
            if (!needle.isEmpty() && !matchesHud(info, needle)) continue;
            add(hudRow(theme, info)).expandX();
            shown++;
        }
        if (shown == 0) {
            add(theme.label(Hud.get().infos().isEmpty()
                ? "No HUD elements are registered."
                : "No HUD elements match \"" + query + "\".").color(theme.muted)).expandX();
        }
    }

    private WTable hudRow(GuiTheme theme, HudElementInfo<?> info) {
        WTable row = theme.table();

        WButton toggle = row.add(theme.button(Hud.get().isActive(info) ? "On" : "Off")).widget();
        toggle.action = () -> {
            Hud.get().toggle(info);
            ConfigManager.get().markDirty();
            reload();
        };

        WVerticalList details = theme.verticalList();
        details.add(theme.label(info.title)).expandX();
        if (info.description != null && !info.description.isBlank()) {
            details.add(theme.label(Messages.truncate(info.description, 72)).color(theme.muted)).expandX();
        }
        row.add(details).expandCellX();

        WButton settings = row.add(theme.button("Settings")).widget();
        settings.action = () -> MinecraftClient.getInstance().setScreen(new HudElementSettingsScreen(info));

        row.finish();
        return row;
    }

    // ------------------------------------------------------------------ theme

    private void renderTheme(GuiTheme theme) {
        add(theme.horizontalSeparator("Colour scheme")).expandX();
        WTable presets = add(theme.table()).expandX().widget();
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            boolean current = GuiThemes.preset() == preset;
            WButton button = presets.add(theme.button(current ? "[" + preset.title() + "]" : preset.title())).widget();
            button.action = () -> {
                GuiThemes.applyPreset(preset);
                ClientConfig.get().setThemePreset(preset);
                ConfigManager.get().markDirty();
                reload();
            };
        }
        presets.finish();

        add(theme.horizontalSeparator("HUD text scale")).expandX();
        WVerticalList scale = add(theme.verticalList()).expandX().widget();
        scale.add(theme.label(String.format(Locale.ROOT, "Current: %.2f", Hud.get().textScale()))).expandX();
        WHorizontalList scaleRow = add(theme.horizontalList()).expandX().widget();
        for (double value : new double[]{0.75, 1.0, 1.25, 1.5, 2.0}) {
            WButton button = scaleRow.add(theme.button(String.format(Locale.ROOT, "%.2f", value))).widget();
            button.action = () -> {
                Hud.get().setTextScale(value);
                ConfigManager.get().markDirty();
                reload();
            };
        }

        add(theme.horizontalSeparator("Command prefix")).expandX();
        WTextBox prefix = add(theme.textBox(ClientConfig.get().prefix(), "e.g. .")).expandX().widget();
        prefix.action = () -> {
            ClientConfig.get().setPrefix(prefix.get());
            ConfigManager.get().markDirty();
        };
    }

    // ------------------------------------------------------------------ about

    private void renderAbout(GuiTheme theme) {
        WVerticalList info = add(theme.verticalList()).expandX().widget();
        info.add(theme.label(Addon.NAME + "  v" + Addon.VERSION)).expandX();
        info.add(theme.label("A standalone chat logging, monitoring and schematic toolkit for Minecraft.")
            .color(theme.muted)).expandX();
        info.add(theme.label("No other mod is required; Baritone is used for navigation only when present.")
            .color(theme.muted)).expandX();

        add(theme.horizontalSeparator("Overview")).expandX();
        WVerticalList counts = add(theme.verticalList()).expandX().widget();
        counts.add(theme.label("Modules: " + Modules.get().getAll().size())).expandX();
        counts.add(theme.label("HUD elements: " + Hud.get().infos().size())).expandX();
        counts.add(theme.label("Categories: " + Modules.get().categories().size())).expandX();
        counts.add(theme.label("Config: " + ConfigManager.get().file())).expandX();

        add(theme.horizontalSeparator("Keys")).expandX();
        WVerticalList keys = add(theme.verticalList()).expandX().widget();
        keys.add(theme.label("Right Shift - open this window").color(theme.muted)).expandX();
        keys.add(theme.label(".log gui - open this window from chat").color(theme.muted)).expandX();
        keys.add(theme.label(".log help - list every command").color(theme.muted)).expandX();

        add(theme.horizontalSeparator("Links")).expandX();
        WVerticalList links = add(theme.verticalList()).expandX().widget();
        links.add(theme.label("Source: github.com/logvex-sg/ChatRecordingAddon-meteor")
            .color(theme.muted)).expandX();
        links.add(theme.label("Licensed under the MIT License.").color(theme.muted)).expandX();
    }

    // ------------------------------------------------------------------ helpers

    private String activeSummary() {
        long active = Modules.get().getAll().stream().filter(Module::isActive).count();
        return active + " of " + Modules.get().getAll().size() + " modules on";
    }

    private boolean matches(Module module, String needle) {
        if (needle.isEmpty()) return true;
        if (module.title.toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (module.name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return module.description != null && module.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean matchesHud(HudElementInfo<?> info, String needle) {
        if (info.title != null && info.title.toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (info.name != null && info.name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return info.description != null && info.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, argb(theme.screenBackground));
        super.render(context, mouseX, mouseY, delta);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
