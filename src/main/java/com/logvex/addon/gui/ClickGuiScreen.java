package com.logvex.addon.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.Addon;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemePreset;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.containers.WCard;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.gui.widgets.pressable.WToggle;
import com.logvex.addon.framework.systems.config.ClientConfig;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import com.logvex.addon.framework.systems.modules.Module;
import com.logvex.addon.framework.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Locale;

/**
 * The addon's main window.
 *
 * <p>Three tabs: Modules, HUD and Theme. Each list is a stack of cards, one per entry, with a big
 * on/off switch on the left and a Settings button on the right; search narrows the list. The aim is
 * that a first-time user can turn something on and open its settings without reading anything.
 */
public class ClickGuiScreen extends WidgetScreen {
    private static final String[] TABS = {"Modules", "HUD", "Theme"};

    private int tab;
    private String query = "";

    public ClickGuiScreen() {
        super(GuiThemes.get(), "Log Addon");
        this.tab = 0;
    }

    /** Opens the window on the HUD tab, used by "Manage HUD" from the editor. */
    public ClickGuiScreen(int tab) {
        super(GuiThemes.get(), "Log Addon");
        this.tab = tab;
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        WHorizontalList header = add(theme.horizontalList()).expandX().widget();
        header.add(theme.label(Addon.NAME + "   v" + Addon.VERSION)).expandX();
        header.add(theme.label(summary()).color(theme.muted)).widget();

        WTable strip = add(theme.table()).expandX().widget();
        for (int i = 0; i < TABS.length; i++) {
            boolean active = i == tab;
            WButton button = strip.add(theme.button(active ? "[ " + TABS[i] + " ]" : TABS[i])).widget();
            int index = i;
            button.action = () -> {
                tab = index;
                reload();
            };
        }
        strip.finish();

        if (tab == 0 || tab == 1) {
            WTextBox search = add(theme.textBox(query, tab == 0 ? "Search modules..." : "Search HUD...")).expandX().widget();
            search.action = () -> {
                query = search.get();
                reload();
            };
        }

        if (tab == 0) renderModules(theme);
        else if (tab == 1) renderHud(theme);
        else renderTheme(theme);

        add(theme.button("Close", () -> MinecraftClient.getInstance().setScreen(parent))).expandX();
    }

    // ------------------------------------------------------------------ modules

    private void renderModules(GuiTheme theme) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (var category : Modules.get().categories()) {
            List<Module> group = Modules.get().getGroup(category).stream()
                .filter(module -> matches(module, needle)).toList();
            if (group.isEmpty()) continue;

            long on = group.stream().filter(Module::isActive).count();
            add(theme.horizontalSeparator(category.name + "   " + on + " / " + group.size() + " on")).expandX();
            for (Module module : group) {
                add(moduleCard(theme, module)).expandX();
                shown++;
            }
        }
        if (shown == 0) empty(theme, "modules", query);
    }

    private WCard moduleCard(GuiTheme theme, Module module) {
        WCard card = theme.card();
        WTable row = theme.table();

        WToggle toggle = row.add(theme.toggle(module.isActive() ? "On" : "Off", module::isActive, value -> {
            module.toggle();
            ConfigManager.get().markDirty();
            reload();
        })).widget();
        toggle.theme = theme;

        WVerticalList details = theme.verticalList();
        details.add(theme.label(module.title)).expandX();
        if (module.description != null && !module.description.isBlank()) {
            details.add(theme.label(Messages.truncate(module.description, 70)).color(theme.muted)).expandX();
        }
        row.add(details).expandCellX();

        WButton settings = row.add(theme.button("Settings")).widget();
        settings.action = () -> MinecraftClient.getInstance().setScreen(new ModuleSettingsScreen(module, this));

        row.finish();
        card.add(row).expandX();
        return card;
    }

    // ------------------------------------------------------------------ hud

    private void renderHud(GuiTheme theme) {
        WHorizontalList actions = theme.horizontalList();
        WButton editor = actions.add(theme.button("Arrange HUD")).expandX().widget();
        editor.action = () -> MinecraftClient.getInstance().setScreen(new HudEditorScreen(this));
        WButton showAll = actions.add(theme.button("Show all")).expandX().widget();
        showAll.action = () -> {
            for (HudElementInfo<?> info : Hud.get().infos()) Hud.get().add(info, 4, 4);
            ConfigManager.get().markDirty();
            reload();
        };
        WButton hideAll = actions.add(theme.button("Hide all")).expandX().widget();
        hideAll.action = () -> {
            for (HudElementInfo<?> info : Hud.get().infos()) {
                if (Hud.get().isActive(info)) Hud.get().remove(Hud.get().instance(info));
            }
            ConfigManager.get().markDirty();
            reload();
        };
        add(actions).expandX();

        String needle = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (HudElementInfo<?> info : Hud.get().infos()) {
            if (!needle.isEmpty() && !matchesHud(info, needle)) continue;
            add(hudCard(theme, info)).expandX();
            shown++;
        }
        if (shown == 0) empty(theme, "HUD elements", query);
    }

    private WCard hudCard(GuiTheme theme, HudElementInfo<?> info) {
        WCard card = theme.card();
        WTable row = theme.table();

        WToggle toggle = row.add(theme.toggle(Hud.get().isActive(info) ? "On" : "Off", () -> Hud.get().isActive(info), value -> {
            Hud.get().toggle(info);
            ConfigManager.get().markDirty();
            reload();
        })).widget();
        toggle.theme = theme;

        WVerticalList details = theme.verticalList();
        details.add(theme.label(info.title)).expandX();
        if (info.description != null && !info.description.isBlank()) {
            details.add(theme.label(Messages.truncate(info.description, 70)).color(theme.muted)).expandX();
        }
        row.add(details).expandCellX();

        WButton settings = row.add(theme.button("Settings")).widget();
        settings.action = () -> MinecraftClient.getInstance().setScreen(new HudElementSettingsScreen(info, this));

        row.finish();
        card.add(row).expandX();
        return card;
    }

    // ------------------------------------------------------------------ theme

    private void renderTheme(GuiTheme theme) {
        add(theme.horizontalSeparator("Colour scheme")).expandX();
        WTable presets = add(theme.table()).expandX().widget();
        for (GuiThemePreset preset : GuiThemePreset.values()) {
            boolean current = GuiThemes.preset() == preset;
            WButton button = presets.add(theme.button(current ? "[ " + preset.title() + " ]" : preset.title())).widget();
            button.action = () -> {
                GuiThemes.applyPreset(preset);
                ClientConfig.get().setThemePreset(preset);
                ConfigManager.get().markDirty();
                reload();
            };
        }
        presets.finish();

        add(theme.horizontalSeparator("HUD text scale")).expandX();
        add(theme.label(String.format(Locale.ROOT, "Current: %.2f", Hud.get().textScale()))).expandX();
        WHorizontalList scaleRow = add(theme.horizontalList()).expandX().widget();
        for (double value : new double[]{0.75, 1.0, 1.25, 1.5, 2.0}) {
            WButton button = scaleRow.add(theme.button(String.format(Locale.ROOT, "%.2f", value))).expandX().widget();
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

        add(theme.horizontalSeparator("About")).expandX();
        WVerticalList about = add(theme.verticalList()).expandX().widget();
        about.add(theme.label("Modules: " + Modules.get().getAll().size()
            + "    HUD elements: " + Hud.get().infos().size())).expandX();
        about.add(theme.label("Right Shift opens this window; .log gui does the same.").color(theme.muted)).expandX();
        about.add(theme.label("Config: " + ConfigManager.get().file()).color(theme.muted)).expandX();
        about.add(theme.label("github.com/logvex-sg/ChatRecordingAddon-meteor  -  MIT").color(theme.muted)).expandX();
    }

    // ------------------------------------------------------------------ helpers

    private void empty(GuiTheme theme, String what, String query) {
        boolean none = what.equals("modules") ? Modules.get().getAll().isEmpty() : Hud.get().infos().isEmpty();
        add(theme.label(none ? "Nothing is registered." : "No " + what + " match \"" + query + "\".").color(theme.muted)).expandX();
    }

    private String summary() {
        long active = Modules.get().getAll().stream().filter(Module::isActive).count();
        long hud = Hud.get().infos().stream().filter(Hud.get()::isActive).count();
        return active + " modules - " + hud + " HUD";
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

    /** The parent this window should return to; kept so screens opened from here can go back. */
    public Screen parentScreen() {
        return parent;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, argb(theme.screenBackground));
        super.render(context, mouseX, mouseY, delta);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return ((c.a == 0 ? 255 : c.a) << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
