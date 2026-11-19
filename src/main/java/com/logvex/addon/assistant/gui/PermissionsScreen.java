package com.logvex.addon.assistant.gui;

import com.logvex.addon.assistant.core.CommandCatalog;
import com.logvex.addon.assistant.core.CommandPermission;
import com.logvex.addon.assistant.core.PermissionLevel;
import com.logvex.addon.assistant.core.PermissionSet;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The assistant permissions screen: one row per command, with its level and whitelist.
 *
 * <p>Every control calls a real {@code AssistantModule} method, so the screen cannot drift from
 * what the runtime enforces. The level selector cycles through the four
 * {@link PermissionLevel}s, which keeps a full feature set reachable without a dropdown widget; the
 * row then shows the level's own description so a user never has to guess what "whitelist" means.
 *
 * <p>The command list comes from {@link CommandCatalog}, not from the configured rules, so a command
 * that has no rule yet is still visible and still shows the level that would govern it. That is the
 * only way a user can see that an unconfigured command is closed.
 */
public class PermissionsScreen extends WindowScreen {
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color ACCENT = new Color(140, 200, 255);
    private static final Color GOOD = new Color(150, 220, 150);
    private static final Color WARN = new Color(255, 200, 120);
    private static final Color BAD = new Color(255, 140, 140);

    private final com.logvex.addon.assistant.modules.AssistantModule module;

    private String search = "";
    private String newUser = "";
    private String newAdmin = "";
    private String editing = "";

    public PermissionsScreen(com.logvex.addon.assistant.modules.AssistantModule module) {
        super(GuiThemes.get(), "Chat Features & Commands");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        if (module == null) {
            add(theme.label("The assistant module is not available.")).expandX();
            return;
        }

        renderHeader();
        add(theme.horizontalSeparator()).expandX();
        renderCommands();
        add(theme.horizontalSeparator()).expandX();
        renderAdministrators();
        add(theme.horizontalSeparator()).expandX();
        renderFooter();
    }

    // ------------------------------------------------------------------ header

    private void renderHeader() {
        PermissionSet permissions = module.permissions();

        WVerticalList info = add(theme.verticalList()).expandX().widget();
        keyValue(info, "Permissions:", permissions.enabled() ? "on" : "off");
        keyValue(info, "Unlisted commands:",
            permissions.defaultPolicy() == PermissionSet.DefaultPolicy.CLOSED
                ? "local-only (closed)" : "open to everyone");
        keyValue(info, "Administrators:", permissions.administrators().isEmpty()
            ? "none configured" : String.join(", ", permissions.administrators()));

        if (!permissions.enabled()) {
            add(theme.label("Permissions are off, so every command is open to everyone. "
                + "Turn them on in the module settings to use the rules below."))
                .expandX().widget().color(WARN);
        }

        add(theme.horizontalSeparator("Search")).expandX();
        WTextBox box = add(theme.textBox(search, "Filter commands...")).expandX().widget();
        box.action = () -> {
            search = box.get();
            taskAfterRender = this::render;
        };
    }

    // ------------------------------------------------------------------ commands

    private void renderCommands() {
        List<CommandCatalog.Command> commands = filtered();
        if (commands.isEmpty()) {
            add(theme.label("No command matches that filter.")).expandX().widget().color(MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Command")).widget().color(MUTED);
        table.add(theme.label("Level")).widget().color(MUTED);
        table.add(theme.label("Whitelist")).expandCellX().widget().color(MUTED);
        table.row();

        for (CommandCatalog.Command command : commands) {
            renderCommandRow(table, command);
        }
    }

    private void renderCommandRow(WTable table, CommandCatalog.Command command) {
        PermissionSet permissions = module.permissions();
        PermissionLevel level = permissions.effectiveLevel(command.name());
        CommandPermission rule = permissions.rule(command.name()).orElse(null);
        boolean restricted = permissions.restricted(command.name());

        table.add(theme.label(command.name())).widget().color(restricted ? BAD : GOOD);

        WButton cycle = table.add(theme.button(level.id())).widget();
        cycle.action = () -> {
            module.setPermission(
                (rule == null ? CommandPermission.open(command.name()) : rule)
                    .withLevel(next(level)));
            taskAfterRender = this::render;
        };

        String users = rule == null || rule.users().isEmpty()
            ? (level == PermissionLevel.WHITELIST ? "(nobody)" : "-")
            : String.join(", ", rule.users());
        table.add(theme.label(users)).expandCellX().widget().color(MUTED);

        WButton edit = table.add(theme.button(editing.equals(command.name()) ? "Close" : "Users")).widget();
        edit.action = () -> {
            editing = editing.equals(command.name()) ? "" : command.name();
            newUser = "";
            taskAfterRender = this::render;
        };

        WButton reset = table.add(theme.button("Reset")).widget();
        reset.action = () -> {
            module.clearPermission(command.name());
            taskAfterRender = this::render;
        };
        table.row();

        // The level's own explanation, so the meaning of each level is never a guess.
        table.add(theme.label("")).widget();
        table.add(theme.label(level.description())).expandCellX().widget().color(MUTED);
        table.row();

        if (editing.equals(command.name())) {
            renderUserEditor(command);
        }
    }

    /** The whitelist editor for one command, shown when its Users button is open. */
    private void renderUserEditor(CommandCatalog.Command command) {
        CommandPermission rule = module.permissionFor(command.name()).orElse(null);
        List<String> users = rule == null ? List.of() : rule.users();

        WTable editor = add(theme.table()).expandX().widget();
        WTextBox entry = editor.add(theme.textBox(newUser, "PlayerOne or PlayerOne=uuid"))
            .expandCellX().widget();
        entry.action = () -> newUser = entry.get();

        WButton add = editor.add(theme.button("Add")).widget();
        add.action = () -> {
            if (module.addWhitelisted(command.name(), newUser)) {
                newUser = "";
                taskAfterRender = this::render;
            }
        };
        WButton close = editor.add(theme.button("Done")).widget();
        close.action = () -> {
            editing = "";
            taskAfterRender = this::render;
        };
        editor.row();

        if (users.isEmpty()) {
            add(theme.label("Nobody is whitelisted for " + command.name()
                + ", so only administrators and the local player can use it."))
                .expandX().widget().color(MUTED);
            return;
        }

        for (String user : users) {
            WTable row = add(theme.table()).expandX().widget();
            row.add(theme.label(user)).expandCellX().widget().color(ACCENT);
            row.add(theme.label(CommandPermission.entryUuid(user) == null
                ? "name only" : "uuid pinned")).widget().color(MUTED);
            WButton remove = row.add(theme.button("Remove")).widget();
            remove.action = () -> {
                module.removeWhitelisted(command.name(), user);
                taskAfterRender = this::render;
            };
            row.row();
        }
    }

    // ------------------------------------------------------------------ administrators

    private void renderAdministrators() {
        add(theme.horizontalSeparator("Administrators")).expandX();
        add(theme.label("Administrators may use any command set to the administrators level."))
            .expandX().widget().color(MUTED);

        WTable addRow = add(theme.table()).expandX().widget();
        WTextBox entry = addRow.add(theme.textBox(newAdmin, "PlayerOne or PlayerOne=uuid"))
            .expandCellX().widget();
        entry.action = () -> newAdmin = entry.get();
        WButton add = addRow.add(theme.button("Add")).widget();
        add.action = () -> {
            if (module.addAdministrator(newAdmin)) {
                newAdmin = "";
                taskAfterRender = this::render;
            }
        };
        addRow.row();

        List<String> admins = module.permissions().administrators();
        if (admins.isEmpty()) {
            add(theme.label("No administrators configured. An administrators-only command "
                + "cannot be used by anyone until one is added."))
                .expandX().widget().color(MUTED);
            return;
        }
        for (String admin : admins) {
            WTable row = add(theme.table()).expandX().widget();
            row.add(theme.label(admin)).expandCellX().widget().color(ACCENT);
            WButton remove = row.add(theme.button("Remove")).widget();
            remove.action = () -> {
                module.removeAdministrator(admin);
                taskAfterRender = this::render;
            };
            row.row();
        }
    }

    // ------------------------------------------------------------------ footer

    private void renderFooter() {
        WTable footer = add(theme.table()).expandX().widget();

        WButton save = footer.add(theme.button("Save")).widget();
        save.action = () -> {
            module.saveMemory();
            module.info("Assistant permissions saved.");
            taskAfterRender = this::render;
        };

        WButton resetAll = footer.add(theme.button("Reset all rules")).widget();
        resetAll.action = () -> {
            module.permissionsSetting().set(List.of());
            module.rebuild();
            taskAfterRender = this::render;
        };

        WButton assistant = footer.add(theme.button("Assistant...")).widget();
        assistant.action = () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(
            new com.logvex.addon.assistant.gui.AssistantScreen(module));
        footer.row();

        add(theme.label("Rules are stored in the module's command-permissions setting and survive "
            + "a restart. A rule for an unknown command is kept but does nothing."))
            .expandX().widget().color(MUTED);
    }

    // ------------------------------------------------------------------ helpers

    private List<CommandCatalog.Command> filtered() {
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<CommandCatalog.Command> out = new ArrayList<>();
        for (CommandCatalog.Command command : CommandCatalog.all()) {
            if (needle.isEmpty()
                || command.name().contains(needle)
                || command.description().toLowerCase(Locale.ROOT).contains(needle)
                || matchesAlias(command, needle)) {
                out.add(command);
            }
        }
        return out;
    }

    private static boolean matchesAlias(CommandCatalog.Command command, String needle) {
        for (String alias : command.aliases()) {
            if (alias.contains(needle)) return true;
        }
        return false;
    }

    /** The next level in the cycle, so one button reaches all four. */
    private static PermissionLevel next(PermissionLevel level) {
        PermissionLevel[] values = PermissionLevel.values();
        return values[(level.ordinal() + 1) % values.length];
    }

    private void keyValue(WVerticalList list, String key, String value) {
        var row = list.add(theme.horizontalList()).expandX().widget();
        row.add(theme.label(key)).widget().color(MUTED);
        row.add(theme.label(value)).expandX().widget();
    }
}
