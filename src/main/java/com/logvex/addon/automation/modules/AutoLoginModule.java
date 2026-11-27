package com.logvex.addon.automation.modules;

import com.logvex.addon.automation.core.LoginAttemptPolicy;
import com.logvex.addon.automation.core.LoginCredentialStore;
import com.logvex.addon.automation.core.LoginPassword;
import com.logvex.addon.automation.core.LoginPromptDetector;
import com.logvex.addon.automation.core.PendingPrompt;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.notify.core.NotificationType;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.orbit.EventHandler;

/**
 * Answers a server's login prompt with the password stored for that server.
 *
 * <p>Many anarchy and cracked servers drop you at a login prompt and require {@code /login <password>}
 * before you can move. Doing that by hand after every reconnect is tedious, so this module does it.
 *
 * <h2>Why it only sends in answer to a prompt</h2>
 * Sending {@code /login ...} unprompted is actively dangerous. On a server with no login plugin the
 * command is not a command at all: the client sends it as a normal chat message, everyone online
 * reads the password, and this addon's own recorder writes it into the archive and possibly to a
 * Discord webhook. The module therefore sends only when {@link LoginPromptDetector} recognises a
 * prompt in a server message, never on join.
 *
 * <h2>Where the password lives</h2>
 * In {@link LoginCredentialStore}, a file of its own — never in a module setting, because Meteor
 * writes settings into the config file that users copy, paste and commit. Nothing in this module
 * prints, logs or records the password.
 */
public class AutoLoginModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> enabledPerServer = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Answer login prompts with the stored password.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("How many prompts to answer before giving up. Kept low: repeated failures look "
            + "like a brute-force attempt and can get the account banned.")
        .defaultValue(LoginAttemptPolicy.DEFAULT_MAX_ATTEMPTS)
        .range(1, 5)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum gap between two sends, so a prompt repeating every tick cannot flood the server.")
        .defaultValue(4)
        .range(1, 300)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a short note when a password is sent or the login succeeds. The password "
            + "itself is never printed.")
        .defaultValue(true)
        .build()
    );

    private final LoginCredentialStore store = new LoginCredentialStore();
    private volatile LoginAttemptPolicy policy = new LoginAttemptPolicy();
    /**
     * The prompt waiting to be answered.
     *
     * <p>Not consumed on arrival: the message handler cannot send, and the send happens on a tick.
     * Holding the prompt instead of a bare string is what lets a prompt survive the tick that could
     * not act on it — see {@link PendingPrompt} for why that was the bug.
     */
    private final PendingPrompt pending = new PendingPrompt();
    private boolean storeLoaded;
    /** Set once the "no password stored" hint has been printed, so a repeating prompt cannot spam. */
    private boolean warnedMissing;
    /** Set once the "this stored password cannot be sent" note has been printed. */
    private boolean warnedInvalid;
    /** Set once the "this server wants a registration" note has been printed. */
    private boolean warnedRegister;

    public AutoLoginModule() {
        super("auto-login", "Answers a server's /login prompt with the password stored for that server.",
            "autologin");
    }

    @Override
    public void onActivate() {
        loadStore();
        newPolicy();
        pending.clear();
        if (store.size() == 0) {
            info("No passwords stored. Set one with %sautologin gui while connected.",
                com.logvex.addon.framework.systems.config.ClientConfig.get().prefix());
        } else {
            info("Auto Login on. %d server%s configured.", store.size(), store.size() == 1 ? "" : "s");
        }
    }

    @Override
    public void onDeactivate() {
        pending.clear();
        policy.reset();
        info("Auto Login off.");
    }

    @Override
    protected void onWorldJoin() {
        // A reconnect is a new session: the previous attempt count and outcome do not carry over.
        newPolicy();
        pending.clear();
        warnedMissing = false;
        warnedInvalid = false;
        warnedRegister = false;
    }

    @Override
    protected void onWorldLeave() {
        policy.reset();
        pending.clear();
    }

    /** Rebuilds the policy so a changed attempt limit or cooldown applies without a reconnect. */
    private void newPolicy() {
        policy = new LoginAttemptPolicy(maxAttempts.get(), cooldownSeconds.get() * 1000L);
    }

    /**
     * Priority for the chat handler: one above {@code HIGHEST}.
     *
     * <p>{@link ReceiveMessageEvent} is cancellable and the bus stops dispatching the moment a
     * listener cancels it, so a login prompt that a hide rule suppresses never reaches this module
     * and the player is never logged in. The chat filter cancels at {@code HIGHEST}, and the bus
     * breaks a priority tie by subscription order — the filter subscribes once at bootstrap, before
     * any module can be enabled, so matching its priority would leave this handler behind it and the
     * bug intact. Being strictly higher is what actually puts this handler first.
     *
     * <p>Reading ahead of the filter is also the safe order: this handler only reads the text and
     * notes that a prompt arrived. It never sends from here, and it never uncancels the event, so a
     * hidden message stays hidden.
     */
    private static final int BEFORE_FILTER = meteordevelopment.orbit.EventPriority.HIGHEST + 1;

    @EventHandler(priority = BEFORE_FILTER)
    private void onMessage(ReceiveMessageEvent event) {
        // Posted for every chat line; a throw here would take the chat pipeline down with it.
        SafeEventHandler.run("AutoLoginModule.onMessage", () -> {
            if (!enabledPerServer.get() || event.getMessage() == null) return;
            boolean fromPlayer = event.getIndicator() != null;
            String text = event.getMessage().getString();
            if (text == null || text.isBlank()) return;

            if (LoginPromptDetector.isLoginSuccess(text, fromPlayer)) {
                policy.markSuccess();
                pending.clear();
                if (announce.get()) info("Logged in.");
                return;
            }
            if (LoginPromptDetector.isLoginFailure(text, fromPlayer)) {
                policy.markFailure();
                pending.clear();
                warning("The server rejected the stored password. Not retrying; fix it with %sautologin gui.",
                    com.logvex.addon.framework.systems.config.ClientConfig.get().prefix());
                notifyUser(NotificationType.CONNECTION, "Auto Login", "Password rejected by the server.");
                return;
            }
            if (LoginPromptDetector.isLoginPrompt(text, fromPlayer)) {
                policy.notePrompt();
                pending.offer(text, System.currentTimeMillis());
                return;
            }
            if (LoginPromptDetector.isRegisterPrompt(text)) {
                // Not an error: the server wants a new account, and /login would just fail. Say so
                // once rather than leaving the user wondering why nothing happened.
                if (!warnedRegister) {
                    warnedRegister = true;
                    warning("This server asks you to register. Auto Login only logs in to an existing account.");
                }
            }
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        SafeEventHandler.guard("AutoLoginModule.onTick", () -> {
            if (!enabledPerServer.get()) return;

            long now = System.currentTimeMillis();
            // Peek, never consume. The prompt is cleared only once it has been answered or is
            // provably unanswerable, so a tick that cannot send leaves it for the next one.
            if (pending.peek(now) == null) return;
            if (mc.player == null || mc.getNetworkHandler() == null) return;

            String password = store.get(serverKey());
            if (password == null || password.isEmpty()) {
                // A missing password is not a rejection: leave the policy alone so setting one takes
                // effect on the very next prompt. Warn once, or a prompt every second would spam chat.
                if (!warnedMissing) {
                    warnedMissing = true;
                    warning("This server asked for a login but no password is stored. Use %sautologin gui to set one.",
                        com.logvex.addon.framework.systems.config.ClientConfig.get().prefix());
                }
                return;
            }

            // A stored value can predate the validation on the setup screen, or arrive from a file the
            // user edited by hand. Sending it would either fail as a wrong password or, with a newline,
            // run a second command — so refuse it and say why instead of burning an attempt.
            LoginPassword.Problem problem = LoginPassword.check(password);
            if (problem != LoginPassword.Problem.OK) {
                if (!warnedInvalid) {
                    warnedInvalid = true;
                    warning("The stored password for %s cannot be sent: %s Fix it with %sautologin gui.",
                        serverKey(), problem.message(),
                        com.logvex.addon.framework.systems.config.ClientConfig.get().prefix());
                }
                return;
            }

            if (!policy.shouldSend(now)) {
                if (policy.exhausted()) {
                    // Out of attempts is final for this session, so the prompt can never be used.
                    pending.clear();
                    warning("Gave up after %d login attempts.", policy.attempts());
                    notifyUser(NotificationType.CONNECTION, "Auto Login", "Gave up after repeated prompts.");
                }
                // A cooldown is temporary: keep the prompt so the send happens when it expires,
                // instead of dropping the only prompt the server was going to give.
                return;
            }

            policy.markSent(now);
            // Consumed here, because this is the point where the prompt has been acted on.
            pending.clear();
            // sendChatCommand strips the leading slash itself, so the password goes out as a command
            // rather than as public chat. This is the one place the value is used, and it is not logged.
            mc.getNetworkHandler().sendChatCommand("login " + password);
            if (announce.get()) {
                info("Sent login for %s (attempt %d of %d).", serverKey(), policy.attempts(), maxAttempts.get());
            }
        }).run();
    }

    private void loadStore() {
        if (storeLoaded) return;
        storeLoaded = true;
        SafeEventHandler.run("AutoLoginModule.loadStore", store::load);
    }

    /** The address used to look up this server's password. */
    public String serverKey() {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            return LoginCredentialStore.key(mc.getCurrentServerEntry().address);
        }
        return mc.isInSingleplayer() ? "singleplayer" : "unknown";
    }

    /** The credential store, for the command that sets and clears passwords. */
    public LoginCredentialStore store() {
        loadStore();
        return store;
    }

    public LoginAttemptPolicy policy() {
        return policy;
    }

    /** How many prompts may be answered before giving up. */
    public int attemptLimit() {
        return maxAttempts.get();
    }

    /** Raises or lowers the attempt limit, clamped to the setting's own range. */
    public void setAttemptLimit(int value) {
        maxAttempts.set(Math.min(5, Math.max(1, value)));
        newPolicy();
    }

    /** Shortest gap between two login sends, in seconds. */
    public int cooldownSeconds() {
        return cooldownSeconds.get();
    }

    /** Raises or lowers the cooldown, clamped to the setting's own range. */
    public void setCooldownSeconds(int value) {
        cooldownSeconds.set(Math.min(300, Math.max(1, value)));
        newPolicy();
    }

    /**
     * The module's own controls: a password field for the current server and a list of every server
     * that already has one.
     *
     * <p>The field is deliberately plain — the password is visible while you type it, so you can
     * check what you entered and fix a typo instead of guessing at bullets. That is a real trade-off:
     * the settings screen can end up in a screenshot or a stream. The field clears itself on save,
     * so the value is only on screen while you are editing it.
     *
     * <p>What is not traded away: the password is written to {@link #store()}, never into a Meteor
     * setting, and no command prints it.
     */
    @Override
    public com.logvex.addon.framework.gui.widgets.WWidget getWidget(
        com.logvex.addon.framework.gui.GuiTheme theme) {
        var list = theme.verticalList();

        list.add(theme.horizontalSeparator("This server"));
        var field = list.add(theme.textBox("", "type the password, then press Enter or Save"))
            .expandX().widget();
        var status = list.add(theme.label(statusText())).expandX().widget();

        var save = list.add(theme.button("Save password for " + serverKey())).expandX().widget();
        save.primary();

        // Declared here because both the save handler and the Forget buttons rebuild the list.
        Runnable[] refresh = new Runnable[1];

        Runnable doSave = () -> {
            // The same validation the setup screen applies. Without it this path stored a value
            // with a trailing space or a pasted newline, reported "Saved", and then refused to send
            // it on every prompt — a saved password that silently never works.
            String value = LoginPassword.tidy(field.get());
            LoginPassword.Problem problem = LoginPassword.check(value);
            if (problem != LoginPassword.Problem.OK) {
                warning("%s", problem.message());
                status.set(problem.message());
                return;
            }
            store().put(serverKey(), value);
            store().save();
            field.set("");
            policy.reset();
            status.set(statusText());
            refresh[0].run();
            info("Saved a password for %s.", serverKey());
        };
        save.action = doSave;
        // Enter commits. This must not be wired to `action`, which fires on every keystroke and
        // would save the password one character at a time.
        field.submit = doSave;

        var clear = list.add(theme.button("Clear password for " + serverKey())).expandX().widget();
        clear.action = () -> {
            if (store().remove(serverKey())) {
                store().save();
                status.set(statusText());
                refresh[0].run();
                info("Cleared the password for %s.", serverKey());
            } else {
                warning("No password stored for %s.", serverKey());
            }
        };

        list.add(theme.horizontalSeparator("All servers"));
        var servers = list.add(theme.verticalList()).expandX().widget();
        // Filled once and rebuilt on a change, so a Forget button takes its own row away instead
        // of leaving a stale entry until the panel is reopened.
        refresh[0] = () -> {
            servers.clear();
            if (store().size() == 0) {
                servers.add(theme.label("Nothing stored yet."));
                return;
            }
            servers.add(theme.label(store().size() + " server"
                + (store().size() == 1 ? "" : "s") + " with a password:"));
            for (String server : store().servers()) {
                var row = servers.add(theme.horizontalList()).expandX().widget();
                String name = server.equals(serverKey()) ? server + "  (here)" : server;
                row.add(theme.label(name)).expandX();
                var forget = row.add(theme.button("Forget")).widget();
                forget.action = () -> {
                    store().remove(server);
                    store().save();
                    status.set(statusText());
                    refresh[0].run();
                    info("Forgot the password for %s.", server);
                };
            }
        };
        refresh[0].run();

        list.add(theme.label("Stored in logclient/" + store().file().getFileName()
            + ", outside the Meteor config."));
        return list;
    }

    /** One line describing whether this server has a password, without revealing it. */
    private String statusText() {
        String password = store().get(serverKey());
        if (password == null || password.isEmpty()) return "No password saved for this server.";
        return "Saved (" + password.length() + " characters).";
    }
}
