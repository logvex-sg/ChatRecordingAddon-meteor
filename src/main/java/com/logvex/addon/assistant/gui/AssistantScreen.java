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
        add(theme.label("Every reply the assistant gives is listed here and every field is editable. "
            + "Nothing is hard-coded: switch a rule off, change what it says, who may fire it, how it "
            + "is delivered and how often.")).expandX().widget().color(MUTED);

        WTable addRow = add(theme.table()).expandX().widget();
        WTextBox pattern = addRow.add(theme.textBox(newPattern, "keyword or !command")).expandCellX().widget();
        pattern.action = () -> newPattern = pattern.get();
        WTextBox reply = addRow.add(theme.textBox(newReply, "reply ({player} = speaker)")).expandCellX().widget();
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

    /**
     * One rule, shown as a header row and an editable panel.
     *
     * <p>The header carries the two things a user scans for — what fires it and what it says — plus
     * the buttons that act on the whole rule. The panel below exposes every remaining field, so no
     * behaviour is reachable only by typing the encoded string. Each edit replaces the stored rule
     * immediately, because a rule list is small and an unsaved edit that is lost on a tab switch is
     * exactly the "it will not let me configure it" complaint.
     */
    private void renderRule(int index, String encoded) {
        TriggerRule rule = TriggerRule.decode(encoded);
        if (rule == null) return;

        WTable row = add(theme.table()).expandX().widget();
        WTextBox patternBox = row.add(theme.textBox(rule.pattern(), "keyword")).expandCellX().widget();
        patternBox.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            TriggerRule edited = (current == null ? rule : current).withPattern(patternBox.get().strip());
            module.replaceRule(index, edited.encode());
        };
        WTextBox replyBox = row.add(theme.textBox(rule.reply(), "reply")).expandCellX().widget();
        replyBox.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            TriggerRule edited = (current == null ? rule : current).withReply(replyBox.get());
            module.replaceRule(index, edited.encode());
        };
        WButton enable = row.add(theme.button(rule.enabled() ? "On" : "Off")).widget();
        enable.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.setRuleEnabled(index, !(current == null ? rule : current).enabled());
            taskAfterRender = this::render;
        };
        WButton remove = row.add(theme.button("Remove")).widget();
        remove.action = () -> {
            module.removeRule(index);
            taskAfterRender = this::render;
        };
        row.row();

        // Delivery and pacing. A picker rather than a text field for the mode, because a typo there
        // silently falls back to the default and the user only finds out when a reply goes public.
        WTable behaviour = add(theme.table()).expandX().widget();
        WButton mode = behaviour.add(theme.button("Where: " + modeLabel(rule.mode()))).widget();
        mode.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            TriggerRule base = current == null ? rule : current;
            module.replaceRule(index, base.withMode(nextMode(base.mode())).encode());
            taskAfterRender = this::render;
        };
        WButton embed = behaviour.add(theme.button("Box: " + rule.embedMode().encoded())).widget();
        embed.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            TriggerRule base = current == null ? rule : current;
            module.replaceRule(index, base.withEmbedMode(nextEmbed(base.embedMode())).encode());
            taskAfterRender = this::render;
        };
        addNumberBox(behaviour, "Cooldown ms", Long.toString(rule.cooldownMs()), value -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index,
                (current == null ? rule : current).withCooldownMs(parseLong(value, rule.cooldownMs())).encode());
        });
        addNumberBox(behaviour, "Per session (0 = no limit)", Integer.toString(rule.sessionLimit()), value -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index,
                (current == null ? rule : current).withSessionLimit((int) parseLong(value, rule.sessionLimit())).encode());
        });
        behaviour.row();

        WTable toggles = add(theme.table()).expandX().widget();
        toggles.add(theme.toggle("Case sensitive", rule::caseSensitive, value -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withCaseSensitive(value).encode());
        })).expandCellX();
        toggles.add(theme.toggle("Whole word", rule::wholeWord, value -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withWholeWord(value).encode());
        })).expandCellX();
        toggles.add(theme.toggle("Sound", rule::sound, value -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withSound(value).encode());
        })).expandCellX();
        toggles.add(theme.toggle("Remember", rule::remember, value -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withRemember(value).encode());
        })).expandCellX();
        toggles.row();

        WTable identity = add(theme.table()).expandX().widget();
        WTextBox senderBox = identity.add(theme.textBox(rule.sender(), "only from player (blank = anyone)"))
            .expandCellX().widget();
        senderBox.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withSender(senderBox.get().strip()).encode());
        };
        WTextBox aliasBox = identity.add(theme.textBox(rule.aliases(), "aliases, comma separated"))
            .expandCellX().widget();
        aliasBox.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withAliases(aliasBox.get()).encode());
        };
        WTextBox poolBox = identity.add(theme.textBox(rule.pool(), "reply variants, separated by |"))
            .expandCellX().widget();
        poolBox.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withPool(poolBox.get()).encode());
        };
        identity.row();

        WTable delivery = add(theme.table()).expandX().widget();
        WTextBox whisperBox = delivery.add(theme.textBox(rule.whisper(), "whisper command, e.g. /msg"))
            .expandCellX().widget();
        whisperBox.action = () -> {
            TriggerRule current = TriggerRule.decode(encodedAt(index, rule));
            module.replaceRule(index, (current == null ? rule : current).withWhisper(whisperBox.get().strip()).encode());
        };
        delivery.row();

        add(theme.horizontalSeparator()).expandX();
    }

    /**
     * Re-reads the rule at {@code index} so an edit builds on the latest stored value.
     *
     * <p>Without this, typing in the pattern box and then in the reply box would write the reply over
     * a rule rebuilt from the text captured when the row was drawn, silently discarding the pattern.
     */
    private String encodedAt(int index, TriggerRule fallback) {
        List<String> current = module.rulesSetting().get();
        if (index < 0 || index >= current.size()) return fallback.encode();
        return current.get(index);
    }

    private void addNumberBox(WTable table, String placeholder, String value,
                              java.util.function.Consumer<String> onEdit) {
        WTextBox box = table.add(theme.textBox(value, placeholder)).widget();
        box.action = () -> onEdit.accept(box.get());
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String modeLabel(TriggerRule.ReplyMode mode) {
        return switch (mode) {
            case CHAT -> "public chat";
            case PRIVATE -> "private message";
            case NOTIFY -> "notification only";
            case SILENT -> "silent";
        };
    }

    private static TriggerRule.ReplyMode nextMode(TriggerRule.ReplyMode mode) {
        TriggerRule.ReplyMode[] all = TriggerRule.ReplyMode.values();
        return all[(mode.ordinal() + 1) % all.length];
    }

    private static TriggerRule.EmbedMode nextEmbed(TriggerRule.EmbedMode mode) {
        TriggerRule.EmbedMode[] all = TriggerRule.EmbedMode.values();
        return all[(mode.ordinal() + 1) % all.length];
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
