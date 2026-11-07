package com.logvex.addon.gui;

import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.LogClientContext;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemePreset;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.utils.Cell;
import com.logvex.addon.framework.gui.widgets.WLabel;
import com.logvex.addon.framework.gui.widgets.WNavItem;
import com.logvex.addon.framework.gui.widgets.WWidget;
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
import com.logvex.addon.framework.utils.render.Render2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Locale;

/**
 * The addon's main window.
 *
 * <p>A navigation rail on the left switches between Modules, HUD, Theme and About; the content area
 * on the right lists the entries for the current page. Each list is a stack of cards, one per entry,
 * with an on/off switch and a Settings button; search narrows the list, and an enabled entry is
 * marked with an accent bar so it can be spotted in a long list at a glance.
 *
 * <p>The rail is drawn from primitives rather than textures, so it needs no art assets and picks up
 * whichever theme is active. The layout is a two-column shell that is laid out every frame, which is
 * what lets the rail and the content scroll independently.
 */
public class ClickGuiScreen extends WidgetScreen {
    private static final String[] TABS = {"Modules", "HUD", "Theme", "About"};
    private static final WNavItem.Glyph[] GLYPHS = {
        WNavItem.Glyph.MODULES, WNavItem.Glyph.HUD, WNavItem.Glyph.THEME, WNavItem.Glyph.INFO
    };
    /** Width of the navigation rail. */
    private static final double RAIL_WIDTH = 108;

    private int tab;
    private String query = "";

    /** The two-column shell: rail on the left, content on the right. */
    private WHorizontalList shell;
    private WVerticalList rail;
    private WVerticalList content;

    public ClickGuiScreen() {
        this(0);
    }

    /** Opens the window on a given tab, used by "Manage HUD" from the editor. */
    public ClickGuiScreen(int tab) {
        super(GuiThemes.get(), "Log Client");
        this.tab = Math.max(0, Math.min(TABS.length - 1, tab));
    }

    @Override
    protected void populateRoot() {
        // Rebuilt on every reload: the root is cleared first, so the shell and its two columns must
        // be recreated rather than reused, or they would be orphaned.
        shell = new WHorizontalList();
        shell.theme = theme;
        shell.spacing = 4;

        rail = new WVerticalList();
        rail.theme = theme;
        rail.spacing = 2;

        content = new WVerticalList();
        content.theme = theme;
        content.spacing = 3;

        shell.add(rail).minWidth(RAIL_WIDTH);
        shell.add(content).expandX();
        root.add(shell).expandX();
    }

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        widget.theme = theme;
        return content.add(widget);
    }

    /** Adds a widget to the rail rather than the content column. */
    private <W extends WWidget> Cell<W> railAdd(W widget) {
        widget.theme = theme;
        return rail.add(widget);
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;
        buildRail(theme);
        buildHeader(theme);

        switch (tab) {
            case 0 -> renderModules(theme);
            case 1 -> renderHud(theme);
            case 2 -> renderTheme(theme);
            default -> renderAbout(theme);
        }
    }

    // ------------------------------------------------------------------ rail

    private void buildRail(GuiTheme theme) {
        WLabel brand = new WLabel(LogClientContext.NAME, theme.text);
        railAdd(brand).expandX();
        railAdd(new WLabel("v" + LogClientContext.VERSION, theme.muted)).expandX();
        railAdd(new com.logvex.addon.framework.gui.widgets.WHorizontalSeparator("")).expandX();

        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            WNavItem item = new WNavItem(TABS[i], GLYPHS[i], i == tab, () -> {
                if (tab != index) {
                    tab = index;
                    query = "";
                    reload();
                }
            });
            railAdd(item).expandX();
        }

        railAdd(new com.logvex.addon.framework.gui.widgets.WHorizontalSeparator("")).expandX();
        long active = Modules.get().getAll().stream().filter(Module::isActive).count();
        long hud = Hud.get().infos().stream().filter(Hud.get()::isActive).count();
        railAdd(new WLabel(active + " on", theme.good)).expandX();
        railAdd(new WLabel(hud + " HUD", theme.accent)).expandX();
    }

    // ------------------------------------------------------------------ header

    private void buildHeader(GuiTheme theme) {
        WHorizontalList header = theme.horizontalList();
        header.add(theme.label(tabTitle()).color(theme.accent)).expandX();
        header.add(theme.label(summary()).color(theme.muted)).widget();
        add(header).expandX();

        if (tab != 3) {
            WTextBox search = add(theme.textBox(query, searchHint())).expandX().widget();
            search.action = () -> {
                query = search.get();
                reload();
            };
        }
    }

    private String tabTitle() {
        return switch (tab) {
            case 0 -> "Modules";
            case 1 -> "HUD elements";
            case 2 -> "Appearance";
            default -> "About";
        };
    }

    private String searchHint() {
        return tab == 0 ? "Search modules..." : "Search HUD elements...";
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
        card.accentBar = module.isActive();
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
        card.accentBar = Hud.get().isActive(info);
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

        // A live swatch strip so the effect of a preset is visible without leaving the page.
        add(theme.horizontalSeparator("Preview")).expandX();
        WCard swatch = theme.card();
        WVerticalList swatchRows = theme.verticalList();
        swatchRows.add(theme.label("The colours this preset derives, in order:").color(theme.muted)).expandX();
        WHorizontalList colours = theme.horizontalList();
        String[] captions = {"accent", "second", "glow", "good", "warn", "bad"};
        com.logvex.addon.framework.utils.render.color.Color[] palette = {
            theme.accent, theme.accentSecondary, theme.glow, theme.good, theme.warn, theme.bad};
        for (int i = 0; i < palette.length; i++) {
            colours.add(theme.swatch(palette[i], captions[i])).expandX();
        }
        swatchRows.add(colours).expandX();
        swatch.add(swatchRows).expandX();
        add(swatch).expandX();

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
    }

    // ------------------------------------------------------------------ about

    private void renderAbout(GuiTheme theme) {
        add(theme.horizontalSeparator("Log Client")).expandX();
        WVerticalList about = add(theme.verticalList()).expandX().widget();
        about.add(theme.label(LogClientContext.NAME + "  v" + LogClientContext.VERSION)).expandX();
        about.add(theme.label("A standalone Fabric client for Minecraft 1.21.1.").color(theme.muted)).expandX();
        about.add(theme.label("No Meteor Client required.").color(theme.muted)).expandX();

        add(theme.horizontalSeparator("Registered")).expandX();
        WVerticalList counts = add(theme.verticalList()).expandX().widget();
        long activeModules = Modules.get().getAll().stream().filter(Module::isActive).count();
        long activeHud = Hud.get().infos().stream().filter(Hud.get()::isActive).count();
        counts.add(theme.label("Modules: " + Modules.get().getAll().size()
            + "  (" + activeModules + " on)")).expandX();
        counts.add(theme.label("HUD elements: " + Hud.get().infos().size()
            + "  (" + activeHud + " shown)")).expandX();
        counts.add(theme.label("Theme: " + GuiThemes.preset().title())).expandX();

        add(theme.horizontalSeparator("Shortcuts")).expandX();
        WVerticalList keys = add(theme.verticalList()).expandX().widget();
        keys.add(theme.label("Right Shift opens this window.").color(theme.muted)).expandX();
        keys.add(theme.label("Type " + ClientConfig.get().prefix() + "log gui in chat to do the same.").color(theme.muted)).expandX();
        keys.add(theme.label("Config: " + ConfigManager.get().file()).color(theme.muted)).expandX();
        keys.add(theme.label("github.com/logvex-sg/ChatRecordingAddon-meteor  -  MIT").color(theme.muted)).expandX();

        add(theme.button("Close", () -> MinecraftClient.getInstance().setScreen(parent))).expandX();
    }

    // ------------------------------------------------------------------ helpers

    private void empty(GuiTheme theme, String what, String query) {
        boolean none = what.equals("modules") ? Modules.get().getAll().isEmpty() : Hud.get().infos().isEmpty();
        WCard card = theme.card();
        WVerticalList column = theme.verticalList();
        column.add(theme.label(none ? "Nothing is registered yet." : "No results").color(theme.text)).expandX();
        column.add(theme.label(none
            ? "Modules appear here once they are registered."
            : "Nothing matches \"" + query + "\". Try a shorter search.").color(theme.muted)).expandX();
        card.add(column).expandX();
        add(card).expandX();
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

    // ------------------------------------------------------------------ layout & render

    @Override
    protected void layoutRoot() {
        // The content column scrolls; the rail is short and always stays fully visible, so the shell
        // itself is given its natural height rather than being clamped and scrolled twice.
        if (content != null) content.maxHeight = Math.max(40, this.height - 30);
        root.maxHeight = Double.MAX_VALUE;
        root.calculateSize();
        double maxWidth = Math.min(Math.max(root.width, RAIL_WIDTH + 240), Math.max(240, this.width - 20));
        root.layout(10, 10, maxWidth);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, Render2D.argb(theme.screenBackground));
        // Lay out before painting the rail's surface, so the surface follows the rail on the first
        // frame instead of being drawn at the origin.
        layoutRoot();
        if (rail != null && theme != null) {
            // The rail is laid out at least RAIL_WIDTH wide by the shell; its own reported width can
            // be narrower, so the surface follows the laid-out column.
            double railWidth = Math.max(rail.width, RAIL_WIDTH);
            Render2D.roundedRect(context, rail.x - 4, rail.y - 4, railWidth + 8, rail.height + 8, 5,
                Render2D.argb(theme.sidebar));
            Render2D.roundedOutline(context, rail.x - 4, rail.y - 4, railWidth + 8, rail.height + 8, 5,
                Render2D.argb(theme.windowBorder, 150), 1);
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
