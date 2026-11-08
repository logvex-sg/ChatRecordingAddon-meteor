package com.logvex.addon.assistant.gui;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.modules.AssistantModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * The assistant manager: a single window with a Triggers tab and a Memory tab.
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
        tabButton(tabs, "Memory", 1);
        tabButton(tabs, "Local AI", 2);
        tabButton(tabs, "Status", 3);

        add(theme.horizontalSeparator()).expandX();
        switch (tab) {
            case 1 -> renderMemory();
            case 2 -> renderAi();
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
        add(theme.label("Format: pattern;sender=;reply=;mode=chat|notify|silent;cooldown=3000;limit=0;sound=false;remember=false;enabled=true"))
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
            module.rulesSetting().set(List.of());
            module.rebuild();
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
            TriggerRule current = TriggerRule.decode(encoded);
            if (current == null) return;
            module.replaceRule(index, new TriggerRule(current.pattern(), current.caseSensitive(),
                current.wholeWord(), current.sender(), current.reply(), current.mode(),
                current.cooldownMs(), current.sessionLimit(), current.sound(), current.remember(),
                !current.enabled()).encode());
            taskAfterRender = this::render;
        };

        WButton remove = row.add(theme.button("Remove")).widget();
        remove.action = () -> {
            module.removeRule(index);
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

    // ------------------------------------------------------------------ local AI

    private void renderAi() {
        com.logvex.addon.ai.core.AiEngine engine = module.aiEngine();

        WVerticalList info = add(theme.verticalList()).expandX().widget();
        keyValue(info, "Model:", engine.enabled() ? "enabled" : "disabled");
        keyValue(info, "Intents:", String.valueOf(engine.model().intentCount()));
        keyValue(info, "Confidence floor:", Math.round(engine.threshold() * 100) + "%");
        keyValue(info, "Classified:", String.valueOf(engine.classified()));
        keyValue(info, "Understood:", engine.understood() + " ("
            + Math.round(engine.understandingRate() * 100) + "%)");
        keyValue(info, "Cache hits:", String.valueOf(engine.cacheHits()));
        keyValue(info, "Runs locally:", "yes \u2014 no network, no external service");
        keyValue(info, "Model file:", com.logvex.addon.ai.mc.AiService.defaultPath().toString());

        add(theme.horizontalSeparator("Intents")).expandX();
        add(theme.label(String.join(", ", engine.model().intentNames())))
            .expandX().widget().color(MUTED);

        add(theme.horizontalSeparator("Ask the model")).expandX();
        WTextBox ask = add(theme.textBox("", "Type a message to see the AI's intent and reply"))
            .expandX().widget();
        WButton run = add(theme.button("Ask")).expandX().widget();
        run.action = () -> {
            String text = ask.get();
            if (text == null || text.isBlank()) return;
            String intent = engine.intentOf(text);
            if (intent == null) {
                add(theme.label("The model does not understand that message."))
                    .expandX().widget().color(MUTED);
                return;
            }
            String reply = engine.respond(text, "Tester", java.util.Map.of(), System.currentTimeMillis());
            add(theme.label("Intent '" + intent + "' (" + Math.round(engine.confidence(text) * 100)
                + "%): " + reply)).expandX().widget().color(ACCENT);
        };

        add(theme.horizontalSeparator("Teach a phrase")).expandX();
        WTextBox intentBox = add(theme.textBox("", "intent name, e.g. greeting")).expandX().widget();
        WTextBox exampleBox = add(theme.textBox("", "an example phrase for that intent")).expandX().widget();
        WButton teach = add(theme.button("Teach")).expandX().widget();
        teach.action = () -> {
            String intent = intentBox.get();
            String example = exampleBox.get();
            if (intent == null || intent.isBlank() || example == null || example.isBlank()) return;
            if (engine.model().teach(intent, example)) {
                module.saveAi();
                taskAfterRender = this::render;
            }
        };

        WTable actions = add(theme.table()).expandX().widget();
        WButton clearStats = actions.add(theme.button("Reset stats")).widget();
        clearStats.action = () -> {
            engine.resetStats();
            taskAfterRender = this::render;
        };
        WButton save = actions.add(theme.button("Save model")).widget();
        save.action = () -> {
            module.saveAi();
            taskAfterRender = this::render;
        };
        actions.row();
    }

    // ------------------------------------------------------------------ status

    private void renderStatus() {
        AssistantMemory memory = module.memory();

        WVerticalList info = add(theme.verticalList()).expandX().widget();
        keyValue(info, "Name:", module.config().name());
        keyValue(info, "Triggers:", String.valueOf(module.triggers().rules().size()));
        keyValue(info, "Facts:", String.valueOf(memory.facts().size()));
        keyValue(info, "Players known:", String.valueOf(memory.knownPlayers().size()));
        keyValue(info, "Remembered lines:", String.valueOf(memory.historySize()));
        keyValue(info, "Handled:", String.valueOf(module.runtime().handled()));
        keyValue(info, "Rate limited:", String.valueOf(module.runtime().rateLimited()));
        keyValue(info, "AI replies:", String.valueOf(module.runtime().aiReplies()));
        keyValue(info, "Memory file:", com.logvex.addon.assistant.mc.FileAssistantStore.defaultPath().toString());

        add(theme.horizontalSeparator("Test a message")).expandX();
        WTextBox test = add(theme.textBox("", "Type a message to test the triggers")).expandX().widget();
        WButton run = add(theme.button("Run test")).expandX().widget();
        run.action = () -> {
            String text = test.get();
            if (text == null || text.isBlank()) return;
            var decision = module.runtime().brain().evaluate("Tester", text, module.triggers(),
                module.config(), System.currentTimeMillis());
            add(theme.label(decision.acts()
                ? "Would " + decision.delivery().name().toLowerCase(java.util.Locale.ROOT) + ": " + decision.text()
                : "No trigger matches that message.")).expandX().widget().color(MUTED);
        };
    }

    private void keyValue(WVerticalList list, String key, String value) {
        var row = list.add(theme.horizontalList()).expandX().widget();
        row.add(theme.label(key)).widget().color(MUTED);
        row.add(theme.label(value)).expandX().widget();
    }
}
