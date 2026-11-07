package com.logvex.addon.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WidgetScreen;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.gui.widgets.pressable.WToggle;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.systems.config.ConfigManager;
import com.logvex.addon.framework.systems.hud.Hud;
import com.logvex.addon.framework.systems.hud.HudElement;
import com.logvex.addon.framework.systems.hud.HudElementInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Locale;

/** Settings for one HUD element, including whether it is currently shown. */
public class HudElementSettingsScreen extends WidgetScreen {
    private final HudElementInfo<?> info;
    private final HudElement element;
    private String query = "";

    public HudElementSettingsScreen(HudElementInfo<?> info) {
        this(info, null);
    }

    public HudElementSettingsScreen(HudElementInfo<?> info, Screen parent) {
        super(GuiThemes.get(), info.title);
        this.info = info;
        this.element = Hud.get().instance(info);
        if (parent != null) this.parent = parent;
    }

    @Override
    public void initWidgets() {
        GuiTheme theme = this.theme;

        WHorizontalList header = theme.horizontalList();
        WButton back = header.add(theme.button("< Back")).widget();
        back.action = () -> MinecraftClient.getInstance().setScreen(
            parent instanceof ClickGuiScreen ? parent : new ClickGuiScreen(1));
        header.add(theme.label(info.title).color(theme.accent)).expandX();
        WToggle shown = header.add(theme.toggle(Hud.get().isActive(info) ? "Shown" : "Hidden",
            () -> Hud.get().isActive(info), value -> {
                Hud.get().toggle(info);
                ConfigManager.get().markDirty();
                reload();
            })).widget();
        shown.theme = theme;
        add(header).expandX();

        if (info.description != null && !info.description.isBlank()) {
            add(theme.label(info.description).color(theme.muted)).expandX();
        }

        WHorizontalList actions = theme.horizontalList();
        WButton arrange = actions.add(theme.button("Arrange in HUD editor")).expandX().widget();
        arrange.action = () -> MinecraftClient.getInstance().setScreen(new HudEditorScreen(this));
        add(actions).expandX();

        WTextBox search = add(theme.textBox(query, "Search settings...")).expandX().widget();
        search.action = () -> {
            query = search.get();
            reload();
        };

        if (element == null) {
            add(theme.label("This element is not available.").color(theme.muted)).expandX();
            return;
        }

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
        } else if (!any) {
            add(theme.label("No settings match \"" + query + "\".").color(theme.muted)).expandX();
        }
    }

    private boolean matches(Setting<?> setting, String needle) {
        if (needle.isEmpty()) return true;
        if (setting.name.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return setting.description != null && setting.description.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, argb(theme.screenBackground));
        root.maxHeight = Math.max(40, this.height - 20);
        super.render(context, mouseX, mouseY, delta);
    }

    private static int argb(com.logvex.addon.framework.utils.render.color.Color c) {
        return ((c.a == 0 ? 255 : c.a) << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}
