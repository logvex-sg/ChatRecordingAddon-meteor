package com.logvex.addon.automation.gui;

import com.logvex.addon.automation.core.LoginPassword;
import com.logvex.addon.automation.modules.AutoLoginModule;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.input.WTextBox;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Everything needed to make auto-login work on this server, in one place.
 *
 * <p>Setting a password used to mean knowing three separate things: that the feature is called
 * "Auto Login", that it lives under {@code !log gui > Modules}, and that the password field is on
 * that module's settings page rather than in a command. {@code !autologin gui} only printed those
 * directions. This screen replaces the directions — it opens straight from the command and carries
 * the field, the on/off switch, the attempt limits and the list of stored servers together.
 *
 * <p>The password is never printed. The status line shows its length only, so a screenshot or a
 * stream cannot expose it, and the field clears itself once the value is saved.
 */
public class AutoLoginScreen extends WindowScreen {
    private static final Color MUTED = new Color(150, 150, 150);
    private static final Color GOOD = new Color(150, 220, 150);
    private static final Color WARN = new Color(255, 200, 120);
    private static final Color BAD = new Color(255, 140, 140);

    private final AutoLoginModule module;

    private String typed = "";
    private String feedback = "";
    private Color feedbackColor = MUTED;

    public AutoLoginScreen(AutoLoginModule module) {
        super(GuiThemes.get(), "Auto Login");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        render();
    }

    private void render() {
        clear();

        if (module == null) {
            add(theme.label("The Auto Login module is not available.")).expandX();
            return;
        }

        renderState();
        add(theme.horizontalSeparator()).expandX();
        renderThisServer();
        add(theme.horizontalSeparator()).expandX();
        renderLimits();
        add(theme.horizontalSeparator()).expandX();
        renderStoredServers();
    }

    // ------------------------------------------------------------------ state

    private void renderState() {
        WVerticalList info = add(theme.verticalList()).expandX().widget();
        keyValue(info, "Status:", module.isActive() ? "on" : "off",
            module.isActive() ? GOOD : WARN);
        keyValue(info, "This server:", module.serverKey(), MUTED);
        keyValue(info, "Password:", statusText(),
            module.store().get(module.serverKey()) == null ? WARN : GOOD);

        if (!module.isActive()) {
            add(theme.label("Auto Login is off, so it will not answer prompts. "
                + "Turn it on below, or with the button.")).expandX().widget().color(WARN);
        }

        WButton toggle = add(theme.button(module.isActive() ? "Turn Auto Login off" : "Turn Auto Login on"))
            .expandX().widget();
        toggle.action = () -> {
            module.toggle();
            taskAfterRender = this::render;
        };
    }

    // ------------------------------------------------------------------ password

    private void renderThisServer() {
        add(theme.horizontalSeparator("Password for " + module.serverKey())).expandX();

        WTextBox field = add(theme.textBox(typed, "type the password, then press Enter"))
            .expandX().widget();
        field.action = () -> typed = field.get();

        Runnable save = () -> {
            String value = LoginPassword.tidy(field.get());
            LoginPassword.Problem problem = LoginPassword.check(value);
            if (problem != LoginPassword.Problem.OK) {
                feedback = problem.message();
                feedbackColor = BAD;
                taskAfterRender = this::render;
                return;
            }
            module.store().put(module.serverKey(), value);
            module.store().save();
            module.policy().reset();
            typed = "";
            field.set("");
            feedback = "Saved. The password will be sent on the next login prompt.";
            feedbackColor = GOOD;
            taskAfterRender = this::render;
        };

        WButton saveButton = add(theme.button("Save password for " + module.serverKey()))
            .expandX().widget();
        saveButton.primary();
        saveButton.action = save;
        // Enter commits. Wiring this to `action` instead would save one character at a time,
        // because `action` fires on every keystroke.
        field.submit = save;

        if (!feedback.isEmpty()) {
            add(theme.label(feedback)).expandX().widget().color(feedbackColor);
        }

        if (module.store().get(module.serverKey()) != null) {
            WButton clear = add(theme.button("Forget the password for this server")).expandX().widget();
            clear.action = () -> {
                module.store().remove(module.serverKey());
                module.store().save();
                feedback = "Forgot the password for " + module.serverKey() + ".";
                feedbackColor = MUTED;
                taskAfterRender = this::render;
            };
        }
    }

    // ------------------------------------------------------------------ limits

    private void renderLimits() {
        add(theme.horizontalSeparator("Limits")).expandX();

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Max attempts")).widget().color(MUTED);
        WButton attempts = table.add(theme.button(String.valueOf(module.attemptLimit()))).widget();
        attempts.action = () -> {
            module.setAttemptLimit(module.attemptLimit() + 1);
            taskAfterRender = this::render;
        };
        table.row();

        table.add(theme.label("Cooldown (seconds)")).widget().color(MUTED);
        WButton cooldown = table.add(theme.button(String.valueOf(module.cooldownSeconds()))).widget();
        cooldown.action = () -> {
            module.setCooldownSeconds(module.cooldownSeconds() + 1);
            taskAfterRender = this::render;
        };
        table.row();

        add(theme.label("Kept low on purpose: repeated failed logins look like a brute-force "
            + "attempt and can get the account banned.")).expandX().widget().color(MUTED);
    }

    // ------------------------------------------------------------------ servers

    private void renderStoredServers() {
        add(theme.horizontalSeparator("All servers")).expandX();

        if (module.store().size() == 0) {
            add(theme.label("No passwords stored yet.")).expandX().widget().color(MUTED);
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        for (String server : module.store().servers()) {
            boolean here = server.equals(module.serverKey());
            table.add(theme.label(here ? server + "  (here)" : server))
                .expandCellX().widget().color(here ? GOOD : MUTED);
            table.add(theme.label("password set")).widget().color(MUTED);

            WButton forget = table.add(theme.button("Forget")).widget();
            forget.action = () -> {
                module.store().remove(server);
                module.store().save();
                taskAfterRender = this::render;
            };
            table.row();
        }

        add(theme.label("Stored in logclient/" + module.store().file().getFileName()
            + ", outside the Meteor config, so it is never exported with your settings."))
            .expandX().widget().color(MUTED);
    }

    // ------------------------------------------------------------------ helpers

    private void keyValue(WVerticalList list, String key, String value, Color color) {
        var row = list.add(theme.horizontalList()).expandX().widget();
        row.add(theme.label(key)).widget().color(MUTED);
        row.add(theme.label(value)).expandX().widget().color(color);
    }

    /** A line describing whether this server has a password, never the value itself. */
    private String statusText() {
        String password = module.store().get(module.serverKey());
        if (password == null || password.isEmpty()) return "not set for this server";
        return "set (" + password.length() + " characters)";
    }
}
