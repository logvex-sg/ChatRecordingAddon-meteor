package com.logvex.addon.bot.gui;

import com.logvex.addon.bot.BotModule;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WHorizontalList;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.profiles.BotProfile;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.List;

import static com.logvex.addon.framework.Addon.mc;

/**
 * The bot profile manager: list every profile, apply one, capture the current settings under a new
 * name, and delete user profiles.
 *
 * <p>Built-in profiles are marked and cannot be renamed or deleted, so a shipped default is always
 * available as a known-good starting point. Everything shown is read live from the profile manager,
 * so a change made here is reflected immediately.
 */
public class ProfileScreen extends WindowScreen {
    private static final Color GREEN = new Color(120, 220, 120);
    private static final Color GREY = new Color(170, 170, 170);
    private static final Color ACCENT = new Color(140, 200, 255);

    private final BotModule module;
    private WTextBox nameBox;
    private WTable table;

    public ProfileScreen(BotModule module) {
        this(GuiThemes.get(), module);
    }

    public ProfileScreen(GuiTheme theme, BotModule module) {
        super(theme, "Bot Profiles");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        WHorizontalList captureRow = add(theme.horizontalList()).expandX().widget();
        nameBox = captureRow.add(theme.textBox("", "new profile name")).expandX().widget();
        WButton save = captureRow.add(theme.button("Save Current")).widget();
        save.action = () -> {
            if (nameBox == null) return;
            if (module.saveProfile(nameBox.get()) != null) {
                nameBox.set("");
                refresh();
            }
        };

        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Profiles")).expandX();
        table = add(theme.table()).expandX().widget();

        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (mc.player != null && mc.player.age % 20 == 0) refresh();
    }

    private void refresh() {
        if (table == null) return;
        table.clear();
        List<BotProfile> profiles = module.profiles().all();
        if (profiles.isEmpty()) {
            table.add(theme.label("No profiles available.")).expandCellX();
            table.row();
            return;
        }
        for (BotProfile profile : profiles) {
            table.add(theme.label(profile.name())
                .color(profile.builtin() ? ACCENT : GREEN)).expandCellX();
            table.add(theme.label(profile.size() + " setting" + (profile.size() == 1 ? "" : "s"))
                .color(GREY));
            table.add(theme.label(profile.builtin() ? "built-in" : "user").color(GREY));

            WButton apply = table.add(theme.button("Apply")).widget();
            apply.action = () -> {
                module.applyProfile(profile.name());
                refresh();
            };
            if (profile.builtin()) {
                table.add(theme.label("-").color(GREY));
            } else {
                WButton delete = table.add(theme.button("Delete")).widget();
                delete.action = () -> {
                    module.deleteProfile(profile.name());
                    refresh();
                };
            }
            table.row();
        }
    }
}
