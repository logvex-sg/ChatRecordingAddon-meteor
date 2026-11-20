package com.logvex.addon.command;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.automation.core.LoginAttemptPolicy;
import com.logvex.addon.automation.core.LoginCredentialStore;
import com.logvex.addon.automation.modules.AutoLoginModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code !autologin} — manage the per-server login password.
 *
 * <p>There is deliberately no {@code !autologin set <password>} form. Anything typed into the
 * command line is written to chat history, which Minecraft keeps on disk and restores into the
 * input box, so a password passed as an argument would be both persisted and displayed. Setting a
 * password goes through the field on the module's own settings page instead; this command opens
 * that page and reports state.
 *
 * <p>No output here ever prints the password, not even a prefix of it.
 */
public class AutoLoginCommand extends Command {
    public AutoLoginCommand() {
        super("autologin", "Manage the per-server login password. Set it in the module's GUI.",
            "login-auto");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            status();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("status").executes(context -> {
            status();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("gui").executes(context -> {
            AutoLoginModule module = module();
            if (module == null) return SINGLE_SUCCESS;
            // Opens the setup screen directly. Printing directions instead meant the user had to
            // find the module under !log gui > Modules and know the field lives on its settings page.
            var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client != null) {
                client.setScreen(new com.logvex.addon.automation.gui.AutoLoginScreen(module));
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("on").executes(context -> {
            AutoLoginModule module = module();
            if (module == null) return SINGLE_SUCCESS;
            if (module.isActive()) info("Auto Login is already on.");
            else module.toggle();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("off").executes(context -> {
            AutoLoginModule module = module();
            if (module == null) return SINGLE_SUCCESS;
            if (!module.isActive()) info("Auto Login is already off.");
            else module.toggle();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            AutoLoginModule module = module();
            if (module == null) return SINGLE_SUCCESS;
            String server = module.serverKey();
            if (module.store().remove(server)) {
                module.store().save();
                info("Cleared the password for %s.", server);
            } else {
                warning("No password stored for %s.", server);
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clearall").executes(context -> {
            AutoLoginModule module = module();
            if (module == null) return SINGLE_SUCCESS;
            int count = module.store().size();
            if (count == 0) {
                info("No passwords are stored.");
                return SINGLE_SUCCESS;
            }
            module.store().clear();
            module.store().save();
            info("Cleared %d stored password%s.", count, count == 1 ? "" : "s");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("servers").executes(context -> {
            AutoLoginModule module = module();
            if (module == null) return SINGLE_SUCCESS;
            List<ReplyFormat.Field> fields = new ArrayList<>();
            for (String server : module.store().servers()) {
                fields.add(new ReplyFormat.Field(server,
                    LoginCredentialStore.masked(module.store().get(server))));
            }
            if (fields.isEmpty()) {
                info("No passwords are stored.");
                return SINGLE_SUCCESS;
            }
            CommandOutput.sendList(this, "Servers with a stored password", fields,
                "The password itself is never shown.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("test").executes(context -> {
            test();
            return SINGLE_SUCCESS;
        }));
    }

    private AutoLoginModule module() {
        AutoLoginModule module = Modules.get().get(AutoLoginModule.class);
        if (module == null) warning("The Auto Login module is not registered.");
        return module;
    }

    /**
     * Reports what would happen if the server prompted right now, without sending anything.
     *
     * <p>Useful before joining a server whose prompt wording you are unsure about: a missing
     * password or a disabled module shows up here instead of as a silent failure in game.
     */
    private void test() {
        AutoLoginModule module = module();
        if (module == null) return;

        String server = module.serverKey();
        String password = module.store().get(server);
        List<ReplyFormat.Field> fields = new ArrayList<>();
        fields.add(new ReplyFormat.Field("Module", module.isActive() ? "on" : "off"));
        fields.add(new ReplyFormat.Field("Server", server));
        fields.add(new ReplyFormat.Field("Password", LoginCredentialStore.masked(password)));
        fields.add(new ReplyFormat.Field("Detector", "ready; only a recognised prompt triggers a send"));
        CommandOutput.send(this, "Auto Login check", fields,
            "Nothing was sent. The password is only used in answer to a server prompt.");
    }

    private void status() {
        AutoLoginModule module = module();
        if (module == null) return;

        String server = module.serverKey();
        LoginAttemptPolicy policy = module.policy();
        List<ReplyFormat.Field> fields = new ArrayList<>();
        fields.add(new ReplyFormat.Field("Module", module.isActive() ? "on" : "off"));
        fields.add(new ReplyFormat.Field("Server", server));
        fields.add(new ReplyFormat.Field("Password", LoginCredentialStore.masked(module.store().get(server))));
        fields.add(new ReplyFormat.Field("This session", sessionLine(policy)));
        if (module.store().size() > 0) {
            fields.add(new ReplyFormat.Field("Servers stored", String.valueOf(module.store().size())));
        }
        CommandOutput.send(this, "Auto Login", fields,
            prefix() + "autologin gui to open the password field");
    }

    private String sessionLine(LoginAttemptPolicy policy) {
        if (policy.succeeded()) return "logged in";
        if (policy.failed()) return "password rejected, not retrying";
        if (policy.exhausted()) return "gave up after " + policy.attempts() + " attempts";
        if (!policy.sawPrompt()) return "no login prompt seen yet";
        return "waiting, " + policy.attempts() + " sent, " + policy.remaining() + " left";
    }

    private static String prefix() {
        return com.logvex.addon.framework.systems.config.ClientConfig.get().prefix();
    }
}
