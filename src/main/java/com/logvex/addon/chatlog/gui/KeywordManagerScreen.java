package com.logvex.addon.chatlog.gui;

import com.logvex.addon.chatlog.core.KeywordRule;
import com.logvex.addon.chatlog.modules.KeywordAlertsModule;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.settings.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the keyword alert rules. Each rule is edited as its encoded form so the full feature set
 * (case sensitivity, whole word, sound, webhook, colour) stays available without a bespoke widget
 * per flag.
 */
public class KeywordManagerScreen extends WindowScreen {
    private final KeywordAlertsModule module;

    public KeywordManagerScreen(KeywordAlertsModule module) {
        super(GuiThemes.get(), "Keyword Rules");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        if (module == null) {
            add(theme.label("The keyword alerts module is not available.")).expandX();
            return;
        }

        add(theme.label("Format: pattern;case=false;whole=false;sound=true;webhook=false;enabled=true;color=0xFFD166"))
            .expandX().widget().color(GuiKit.MUTED);
        add(theme.horizontalSeparator()).expandX();

        List<String> rules = new ArrayList<>(rulesSetting().get());
        for (int i = 0; i < rules.size(); i++) {
            renderRule(i, rules.get(i));
        }

        add(theme.horizontalSeparator("Add rule")).expandX();
        WTextBox newRule = add(theme.textBox("", "keyword")).expandX().widget();
        WButton add = add(theme.button("Add")).expandX().widget();
        add.action = () -> {
            String value = newRule.get();
            if (value == null || value.isBlank()) return;
            List<String> updated = new ArrayList<>(rulesSetting().get());
            updated.add(KeywordRule.of(value.strip()).encode());
            rulesSetting().set(updated);
            taskAfterRender = this::render;
        };

        WTable defaults = add(theme.table()).expandX().widget();
        WButton restore = defaults.add(theme.button("Restore defaults")).widget();
        restore.action = () -> {
            rulesSetting().set(KeywordRule.defaults().stream().map(KeywordRule::encode).toList());
            taskAfterRender = this::render;
        };
        WButton clear = defaults.add(theme.button("Remove all")).widget();
        clear.action = () -> {
            rulesSetting().set(List.of());
            taskAfterRender = this::render;
        };
    }

    private void renderRule(int index, String encoded) {
        WTable row = add(theme.table()).expandX().widget();

        WTextBox box = row.add(theme.textBox(encoded)).expandCellX().widget();
        box.action = () -> applyEdit(index, box.get());

        WButton enable = row.add(theme.button(isEnabled(encoded) ? "Disable" : "Enable")).widget();
        enable.action = () -> {
            KeywordRule rule = KeywordRule.decode(encoded);
            if (rule == null) return;
            applyEdit(index, new KeywordRule(rule.pattern(), rule.caseSensitive(), rule.wholeWord(),
                rule.sound(), rule.webhook(), !rule.enabled(), rule.colorRgb()).encode());
        };

        WButton remove = row.add(theme.button("Remove")).widget();
        remove.action = () -> {
            List<String> updated = new ArrayList<>(rulesSetting().get());
            if (index >= 0 && index < updated.size()) updated.remove(index);
            rulesSetting().set(updated);
            taskAfterRender = this::render;
        };
    }

    private void applyEdit(int index, String value) {
        List<String> updated = new ArrayList<>(rulesSetting().get());
        if (index < 0 || index >= updated.size()) return;
        KeywordRule rule = KeywordRule.decode(value);
        updated.set(index, rule == null ? value : rule.encode());
        rulesSetting().set(updated);
        taskAfterRender = this::render;
    }

    private static boolean isEnabled(String encoded) {
        KeywordRule rule = KeywordRule.decode(encoded);
        return rule == null || rule.enabled();
    }

    @SuppressWarnings("unchecked")
    private Setting<List<String>> rulesSetting() {
        return (Setting<List<String>>) module.settings.get("rules");
    }
}
