package com.logvex.addon.assistant.gui;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.ChatActivity;
import com.logvex.addon.assistant.core.ChatMacro;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.modules.AssistantModule;
import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommand;
import com.logvex.addon.stats.StatusCommands;
import com.logvex.addon.stats.StatusOutput;
import com.logvex.addon.stats.StatusTemplate;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * The assistant manager: triggers, macros, memory and status, one tab each.
 *
 * <p>Every control is wired to real {@link AssistantModule} methods, so the screen can never drift
 * from the module's behaviour. A rule is edited as its encoded string, which keeps the full feature
 * set (sender filter, reply mode, cooldown, session limit, sound, remember) available without a
 * bespoke widget per flag.
 */
public class AssistantScreen extends WindowScreen {
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color ACCENT = new Color(140, 200, 255);

    private final AssistantModule module;
    private int tab;

    private String filter = "";
    private String newPattern = "";
    private String newReply = "";
    private String newFactKey = "";
    private String newFactValue = "";
    private String newMacro = "";
    private String macroNotice = "";

    public AssistantScreen(AssistantModule module) {
        super(GuiThemes.get(), "Assistant");
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

        WTable tabs = add(theme.table()).expandX().widget();
        tabButton(tabs, "Triggers", 0);
        tabButton(tabs, "Macros", 1);
        tabButton(tabs, "Memory", 2);
        tabButton(tabs, "Status", 3);

        add(theme.horizontalSeparator()).expandX();
        switch (tab) {
            case 1 -> renderMacros();
            case 2 -> renderMemory();
            case 3 -> renderStatus();
            default -> renderTriggers();
        }
    }

    private void tabButton(WTable table, String label, int index) {
        WButton button = table.add(theme.button(index == tab ? "[" + label + "]" : label)).widget();
        button.action = () -> {
            tab = index;
            taskAfterRender = this::render;
        };
    }

    // ------------------------------------------------------------------ triggers

    private void renderTriggers() {
        add(theme.label("Format: pattern;sender=;reply=;mode=chat|notify|silent;cooldown="
            + com.logvex.addon.assistant.core.TriggerRule.DEFAULT_COOLDOWN_MS
            + ";limit=0;sound=false;remember=false;enabled=true"))
            .expandX().widget().color(MUTED);

        WTable addRow = add(theme.table()).expandX().widget();
        WTextBox pattern = addRow.add(theme.textBox(newPattern, "keyword")).expandCellX().widget();
        pattern.action = () -> newPattern = pattern.get();
        WTextBox reply = addRow.add(theme.textBox(newReply, "reply")).expandCellX().widget();
        reply.action = () -> newReply = reply.get();
        WButton add = addRow.add(theme.button("Add")).widget();
        add.action = () -> {
            if (newPattern.isBlank()) return;
            module.addRule(TriggerRule.of(newPattern.strip(), newReply));
            newPattern = "";
            newReply = "";
            taskAfterRender = this::render;
        };
        addRow.row();

        List<String> rules = new ArrayList<>(module.rulesSetting().get());
        if (rules.isEmpty()) {
            add(theme.label("No triggers configured. Add one above.")).expandX().widget().color(MUTED);
        } else {
            for (int i = 0; i < rules.size(); i++) {
                renderRule(i, rules.get(i));
            }
        }

        WTable footer = add(theme.table()).expandX().widget();
        WButton restore = footer.add(theme.button("Restore defaults")).widget();
        restore.action = () -> {
            module.restoreDefaultRules();
            taskAfterRender = this::render;
        };
        WButton clear = footer.add(theme.button("Remove all")).widget();
        clear.action = () -> {
            // Through the module, not the setting: emptying the list alone leaves every shipped
            // rule to be merged straight back in on the next rebuild, so the table never emptied.
            module.removeAllRules();
            taskAfterRender = this::render;
        };
        footer.row();
    }

    private void renderRule(int index, String encoded) {
        WTable row = add(theme.table()).expandX().widget();

        WTextBox box = row.add(theme.textBox(encoded)).expandCellX().widget();
        box.action = () -> module.replaceRule(index, box.get());

        TriggerRule rule = TriggerRule.decode(encoded);
        WButton enable = row.add(theme.button(rule == null || rule.enabled() ? "Disable" : "Enable")).widget();
        enable.action = () -> {
            // Read the box, not the text captured when the row was built: after an edit the box is
            // newer, and toggling the stale copy would silently undo what the user just typed.
            TriggerRule current = TriggerRule.decode(box.get());
            if (current == null) return;
            module.replaceRule(index, new TriggerRule(current.pattern(), current.caseSensitive(),
                current.wholeWord(), current.sender(), current.reply(), current.mode(),
                current.cooldownMs(), current.sessionLimit(), current.sound(), current.remember(),
                !current.enabled(), current.aliases(), current.pool(), current.whisperCommand(),
                current.embedMode()).encode());
            taskAfterRender = this::render;
        };

        WButton remove = row.add(theme.button("Remove")).widget();
        remove.action = () -> {
            module.removeRule(index);
            taskAfterRender = this::render;
        };
        row.row();
    }

    // ------------------------------------------------------------------ macros

    /**
     * The macro editor: one row per shortcut, plus a box to add one.
     *
     * <p>The syntax is shown in full because it is the whole interface: a user who can read the
     * example can write a macro, and no dialog is needed.
     */
    private void renderMacros() {
        add(theme.label("A shortcut you type becomes a server command. Nothing is posted to chat."))
            .expandX().widget().color(MUTED);
        add(theme.label("Format: shortcut <arg> => /command {arg}")).expandX().widget().color(MUTED);
        add(theme.label("Example: !tpa <player> => /tpa {player}   |   !spawn => /spawn"))
            .expandX().widget().color(MUTED);

        List<String> duplicates = module.macroDuplicates();
        if (!duplicates.isEmpty()) {
            add(theme.label("Two macros share: " + String.join(", ", duplicates)
                + " - only the first can run.")).expandX().widget();
        }
        if (!macroNotice.isBlank()) {
            add(theme.label(macroNotice)).expandX().widget().color(MUTED);
        }

        WTable addRow = add(theme.table()).expandX().widget();
        WTextBox line = addRow.add(theme.textBox(newMacro, "!shortcut <arg> => /command {arg}"))
            .expandCellX().widget();
        line.action = () -> newMacro = line.get();
        WButton add = addRow.add(theme.button("Add")).widget();
        add.action = () -> {
            if (newMacro.isBlank()) return;
            // A rejected line stays in the box so the user can fix it instead of retyping it.
            if (!module.addMacro(newMacro.strip())) {
                macroNotice = "That line is not a macro. It needs 'shortcut => /command'.";
                taskAfterRender = this::render;
                return;
            }
            newMacro = "";
            macroNotice = "";
            taskAfterRender = this::render;
        };
        addRow.row();

        List<String> lines = new ArrayList<>(module.macroLinesSetting().get());
        if (lines.isEmpty()) {
            add(theme.label("No macros configured. Add one above.")).expandX().widget().color(MUTED);
        } else {
            for (int i = 0; i < lines.size(); i++) {
                renderMacro(i, lines.get(i));
            }
        }

        WTable footer = add(theme.table()).expandX().widget();
        WButton restore = footer.add(theme.button("Restore defaults")).widget();
        restore.action = () -> {
            module.restoreDefaultMacros();
            macroNotice = "";
            taskAfterRender = this::render;
        };
        WButton clear = footer.add(theme.button("Remove all")).widget();
        clear.action = () -> {
            // The setting's onChanged already rebuilds, so calling it here would rebuild twice.
            module.macroLinesSetting().set(List.of());
            macroNotice = "";
            taskAfterRender = this::render;
        };
        footer.row();
    }

    private void renderMacro(int index, String encoded) {
        WTable row = add(theme.table()).expandX().widget();

        WTextBox box = row.add(theme.textBox(encoded)).expandCellX().widget();
        box.action = () -> {
            // An unparseable edit is refused, so the stored line is never replaced by garbage.
            if (!module.replaceMacro(index, box.get())) {
                macroNotice = "That line is not a macro, so the stored one was kept.";
            } else {
                macroNotice = "";
            }
            taskAfterRender = this::render;
        };

        ChatMacro macro = ChatMacro.decode(encoded);
        String toggle = macro == null ? "Invalid" : macro.enabled() ? "Disable" : "Enable";
        WButton enable = row.add(theme.button(toggle)).widget();
        enable.action = () -> {
            ChatMacro current = ChatMacro.decode(encoded);
            if (current == null) return;
            module.replaceMacro(index, current.with(current.shortcut(), current.usage(),
                current.template(), current.description(), !current.enabled(),
                current.aliases()).encode());
            taskAfterRender = this::render;
        };

        WButton remove = row.add(theme.button("Remove")).widget();
        remove.action = () -> {
            module.removeMacro(index);
            taskAfterRender = this::render;
        };
        row.row();
    }

    // ------------------------------------------------------------------ memory

    private void renderMemory() {
        AssistantMemory memory = module.memory();

        WTable addRow = add(theme.table()).expandX().widget();
        WTextBox key = addRow.add(theme.textBox(newFactKey, "fact name")).expandCellX().widget();
        key.action = () -> newFactKey = key.get();
        WTextBox value = addRow.add(theme.textBox(newFactValue, "value")).expandCellX().widget();
        value.action = () -> newFactValue = value.get();
        WButton save = addRow.add(theme.button("Remember")).widget();
        save.action = () -> {
            if (newFactKey.isBlank()) return;
            memory.rememberFact(newFactKey.strip(), newFactValue, System.currentTimeMillis());
            module.saveMemory();
            newFactKey = "";
            newFactValue = "";
            taskAfterRender = this::render;
        };
        addRow.row();

        WTextBox search = add(theme.textBox(filter, "Filter facts...")).expandX().widget();
        search.action = () -> {
            filter = search.get();
            taskAfterRender = this::render;
        };

        List<AssistantMemory.Fact> facts = memory.facts();
        String needle = filter.trim().toLowerCase(java.util.Locale.ROOT);
        int shown = 0;

        if (!facts.isEmpty()) {
            WTable table = add(theme.table()).expandX().widget();
            table.add(theme.label("Fact")).widget().color(MUTED);
            table.add(theme.label("Value")).expandCellX().widget().color(MUTED);
            table.add(theme.label("Updated")).widget().color(MUTED);
            table.row();

            long now = System.currentTimeMillis();
            for (AssistantMemory.Fact fact : facts) {
                if (!needle.isEmpty() && !fact.key().contains(needle)
                    && !fact.value().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
                table.add(theme.label(fact.key())).widget().color(ACCENT);
                table.add(theme.label(Messages.truncate(fact.value(), 50))).expandCellX().widget();
                table.add(theme.label(Messages.ago(fact.updatedAt(), now))).widget().color(MUTED);
                WButton remove = table.add(theme.button("Forget")).widget();
                remove.action = () -> {
                    memory.forgetFact(fact.key());
                    module.saveMemory();
                    taskAfterRender = this::render;
                };
                table.row();
                shown++;
            }
        }
        if (shown == 0) {
            add(theme.label(facts.isEmpty() ? "No facts stored yet." : "No facts match that filter."))
                .expandX().widget().color(MUTED);
        }

        add(theme.horizontalSeparator("Recent conversations")).expandX();
        List<AssistantMemory.ConversationEntry> history = memory.history(10);
        if (history.isEmpty()) {
            add(theme.label("Nothing remembered yet.")).expandX().widget().color(MUTED);
        } else {
            for (AssistantMemory.ConversationEntry entry : history) {
                add(theme.label(Messages.clock(entry.timestamp()) + "  "
                    + Messages.player(entry.player()) + ": " + Messages.truncate(entry.question(), 40)
                    + "  ->  " + Messages.truncate(entry.answer(), 40)))
                    .expandX().widget().color(MUTED);
            }
        }

        WTable footer = add(theme.table()).expandX().widget();
        WButton clearHistory = footer.add(theme.button("Clear history")).widget();
        clearHistory.action = () -> {
            memory.clearHistory();
            module.saveMemory();
            taskAfterRender = this::render;
        };
        WButton clearAll = footer.add(theme.button("Clear all memory")).widget();
        clearAll.action = () -> {
            module.clearMemory();
            taskAfterRender = this::render;
        };
        footer.row();
    }

    // ------------------------------------------------------------------ status

    private void renderStatus() {
        // A tab that throws leaves the user with a blank window and no way back, so the whole tab is
        // built defensively: any failure is shown in place rather than escaping the screen.
        try {
            renderStatusBody();
        } catch (RuntimeException failure) {
            add(theme.label("The status tab could not be built: " + failure)).expandX().widget()
                .color(MUTED);
        }
    }

    private void renderStatusBody() {
        if (!module.statusEnabled()) {
            add(theme.label("The live status commands are switched off in the module settings."))
                .expandX().widget().color(MUTED);
            return;
        }

        ServerStats stats = module.liveStats();
        add(theme.label(stats.offline()
            ? "Join a world to preview live figures; every figure reads n/a while disconnected."
            : "Each command as it would answer you right now."))
            .expandX().widget().color(MUTED);

        // Previews go through the same path chat uses, so a custom template shows exactly what it
        // would deliver rather than a second, drifting rendering.
        java.util.List<String> stored = module.statusLinesSetting() == null
            ? java.util.List.of() : module.statusLinesSetting().get();
        StatusCommands.Settings settings =
            new StatusCommands.Settings(true, StatusOutput.decodeAll(stored));

        WVerticalList previews = add(theme.verticalList()).expandX().widget();
        for (StatusCommand.Definition command : StatusCommand.all()) {
            StatusCommands.Answer answer =
                StatusCommands.answer(command.name(), stats, settings, module.selfName())
                    .orElse(null);
            String badge = answer == null ? "off" : answer.localOnly() ? "local" : "chat";
            keyValue(previews, module.config().commandPrefix() + command.name() + " [" + badge + "]",
                answer == null ? "(switched off)" : answer.text());
        }

        add(theme.horizontalSeparator("Placeholders")).expandX();
        add(theme.label("Write these in a status template. A figure the client cannot read shows n/a."))
            .expandX().widget().color(MUTED);
        WVerticalList placeholders = add(theme.verticalList()).expandX().widget();
        for (StatusTemplate.Placeholder placeholder : StatusTemplate.placeholders()) {
            keyValue(placeholders, "{" + placeholder.name() + "} e.g. " + placeholder.sample(),
                placeholder.detail());
        }

        add(theme.horizontalSeparator("This session")).expandX();
        WVerticalList info = add(theme.verticalList()).expandX().widget();
        AssistantMemory memory = module.memory();
        keyValue(info, "Triggers:", String.valueOf(module.triggers().rules().size()));
        keyValue(info, "Facts:", String.valueOf(memory.facts().size()));
        keyValue(info, "Handled:", String.valueOf(module.runtime().handled()));
        keyValue(info, "Refused:", String.valueOf(module.runtime().denied()));

        add(theme.horizontalSeparator("Test a message")).expandX();
        WTextBox test = add(theme.textBox("", "Type a message to test the triggers")).expandX().widget();
        WButton run = add(theme.button("Run test")).expandX().widget();
        run.action = () -> {
            String text = test.get();
            if (text == null || text.isBlank()) return;
            // Goes through the live runtime, so the result includes the permission decision a real
            // chat line would get rather than only the trigger match.
            var decision = module.simulate(module.selfName(), text);
            String message;
            if (decision.denied()) {
                message = "Refused: " + decision.text();
            } else if (decision.acts()) {
                message = "Would " + decision.delivery().name().toLowerCase(java.util.Locale.ROOT)
                    + ": " + decision.text();
            } else {
                message = "No trigger or command matches that message.";
            }
            add(theme.label(message)).expandX().widget().color(MUTED);
        };
    }

    private void keyValue(WVerticalList list, String key, String value) {
        var row = list.add(theme.horizontalList()).expandX().widget();
        row.add(theme.label(key)).widget().color(MUTED);
        row.add(theme.label(value)).expandX().widget();
    }
}
