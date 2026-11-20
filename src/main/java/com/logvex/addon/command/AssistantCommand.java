package com.logvex.addon.command;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.modules.AssistantModule;
import com.logvex.addon.common.Messages;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;

import java.util.List;
import java.util.Locale;

/**
 * The {@code .ai} command tree: the text front end for the assistant.
 *
 * <p>It is a thin wrapper over the same {@link AssistantModule} methods the GUI uses, so a command
 * never diverges from the GUI. Every branch resolves the live module (never a cached instance),
 * validates its input and explains a failure in plain language.
 */
public class AssistantCommand extends Command {
    public AssistantCommand() {
        super("ai", "Assistant: triggers, memory and test replies.", "assistant");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            summary();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("status").executes(context -> {
            status();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("help").executes(context -> {
            help();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("caps").executes(context -> {
            capabilities();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("placeholders")
            .executes(context -> {
                placeholders("");
                return SINGLE_SUCCESS;
            })
            .then(argument("section", StringArgumentType.word()).executes(context -> {
                placeholders(StringArgumentType.getString(context, "section"));
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("perms").executes(context -> {
            permissions();
            return SINGLE_SUCCESS;
        })
            .then(literal("list").executes(context -> {
                permissions();
                return SINGLE_SUCCESS;
            }))
            .then(literal("gui").executes(context -> {
                AssistantModule module = assistant();
                if (module == null) {
                    error("The assistant module is not available.");
                    return SINGLE_SUCCESS;
                }
                mc.setScreen(new com.logvex.addon.assistant.gui.PermissionsScreen(module));
                return SINGLE_SUCCESS;
            }))
            .then(literal("set")
                .then(argument("command", StringArgumentType.word())
                    .then(argument("level", StringArgumentType.word()).executes(context -> {
                        setPermission(StringArgumentType.getString(context, "command"),
                            StringArgumentType.getString(context, "level"));
                        return SINGLE_SUCCESS;
                    }))))
            .then(literal("allow")
                .then(argument("command", StringArgumentType.word())
                    .then(argument("user", StringArgumentType.word()).executes(context -> {
                        allowUser(StringArgumentType.getString(context, "command"),
                            StringArgumentType.getString(context, "user"));
                        return SINGLE_SUCCESS;
                    }))))
            .then(literal("deny")
                .then(argument("command", StringArgumentType.word())
                    .then(argument("user", StringArgumentType.word()).executes(context -> {
                        denyUser(StringArgumentType.getString(context, "command"),
                            StringArgumentType.getString(context, "user"));
                        return SINGLE_SUCCESS;
                    }))))
            .then(literal("admin")
                .then(literal("add")
                    .then(argument("user", StringArgumentType.word()).executes(context -> {
                        addAdmin(StringArgumentType.getString(context, "user"));
                        return SINGLE_SUCCESS;
                    })))
                .then(literal("remove")
                    .then(argument("user", StringArgumentType.word()).executes(context -> {
                        removeAdmin(StringArgumentType.getString(context, "user"));
                        return SINGLE_SUCCESS;
                    }))))
            .then(literal("reset")
                .then(argument("command", StringArgumentType.word()).executes(context -> {
                    resetPermission(StringArgumentType.getString(context, "command"));
                    return SINGLE_SUCCESS;
                }))));

        builder.then(literal("gui").executes(context -> {
            AssistantModule module = assistant();
            if (module == null) {
                error("The assistant module is not available.");
                return SINGLE_SUCCESS;
            }
            mc.setScreen(new com.logvex.addon.assistant.gui.AssistantScreen(module));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("triggers").executes(context -> {
            triggerSummary();
            return SINGLE_SUCCESS;
        }).then(literal("list").executes(context -> {
            triggers();
            return SINGLE_SUCCESS;
        })).then(literal("add")
            .then(argument("pattern", StringArgumentType.word())
                .then(argument("reply", StringArgumentType.greedyString()).executes(context -> {
                    addTrigger(StringArgumentType.getString(context, "pattern"),
                        StringArgumentType.getString(context, "reply"));
                    return SINGLE_SUCCESS;
                }))))
            .then(literal("remove")
                .then(argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                    .executes(context -> {
                        removeTrigger(com.mojang.brigadier.arguments.IntegerArgumentType
                            .getInteger(context, "index"));
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("restore").executes(context -> {
                AssistantModule module = assistant();
                if (module == null) {
                    error("The assistant module is not available.");
                    return SINGLE_SUCCESS;
                }
                module.restoreDefaultRules();
                info("Restored the default triggers.");
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("facts").executes(context -> {
            facts();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("remember")
            .then(argument("key", StringArgumentType.word())
                .then(argument("value", StringArgumentType.greedyString()).executes(context -> {
                    remember(StringArgumentType.getString(context, "key"),
                        StringArgumentType.getString(context, "value"));
                    return SINGLE_SUCCESS;
                }))));

        builder.then(literal("forget")
            .then(argument("key", StringArgumentType.greedyString()).executes(context -> {
                forget(StringArgumentType.getString(context, "key"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("test")
            .then(argument("message", StringArgumentType.greedyString()).executes(context -> {
                test(StringArgumentType.getString(context, "message"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("history").executes(context -> {
            history();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            AssistantModule module = assistant();
            if (module == null) {
                error("The assistant module is not available.");
                return SINGLE_SUCCESS;
            }
            module.clearMemory();
            info("Assistant memory cleared.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("save").executes(context -> {
            AssistantModule module = assistant();
            if (module == null) {
                error("The assistant module is not available.");
                return SINGLE_SUCCESS;
            }
            module.saveMemory();
            info("Assistant memory saved.");
            return SINGLE_SUCCESS;
        }));
    }

    /** The addon's configured command prefix, so this list matches what the user actually types. */
    private static String pfx() {
        return com.logvex.addon.framework.systems.config.ClientConfig.get().prefix();
    }

    private void help() {
        List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        addHelp(fields, "status", "assistant state and memory summary.");
        addHelp(fields, "gui", "open the assistant manager.");
        addHelp(fields, "triggers [list|add <pattern> <reply>|remove <n>|restore]",
            "keyword triggers and their replies.");
        addHelp(fields, "facts", "list remembered facts.");
        addHelp(fields, "remember <key> <value>", "store a fact.");
        addHelp(fields, "forget <key>", "remove a fact.");
        addHelp(fields, "test <message>", "see what the assistant would do.");
        addHelp(fields, "history", "recent remembered exchanges.");
        addHelp(fields, "placeholders [section]", "the reply placeholders, one section at a time.");
        addHelp(fields, "perms [list|gui|set|allow|deny|admin|reset]", "who may run each command.");
        addHelp(fields, "clear", "clear all memory.");
        CommandOutput.sendList(this, "Assistant commands", fields, "");
    }

    private void addHelp(List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields,
                         String usage, String description) {
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
            pfx() + "ai " + usage, description));
    }

    /**
     * Lists the placeholders a reply template can use, one section at a time.
     *
     * <p>There are close to thirty of them, and printing every one in a single go pushed the chat
     * box off the screen — the reader could not find the one they wanted and the rest of the
     * conversation scrolled away. With no argument this shows the sections and their counts;
     * naming a section lists just that section, which fits on one screen.
     */
    private void placeholders(String section) {
        AssistantModule module = assistant();
        com.logvex.addon.assistant.core.Placeholders registry =
            com.logvex.addon.assistant.core.ResponseTemplate.registry();
        com.logvex.addon.stats.ServerStats stats = module == null
            ? com.logvex.addon.stats.ServerStats.OFFLINE
            : module.liveStats();
        com.logvex.addon.assistant.core.ResponseTemplate.Context ctx =
            new com.logvex.addon.assistant.core.ResponseTemplate.Context(
                mc.player == null ? "" : mc.player.getGameProfile().getName(), "", stats.server(),
                module == null ? null : module.memory(),
                System.currentTimeMillis(), stats);

        java.util.Map<String, java.util.List<String>> grouped = registry.byGroup();
        if (section == null || section.isBlank()) {
            java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields =
                new java.util.ArrayList<>();
            for (var entry : grouped.entrySet()) {
                fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(entry.getKey(),
                    com.logvex.addon.common.Messages.plural(entry.getValue().size(), "placeholder")));
            }
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Families",
                com.logvex.addon.common.Messages.plural(registry.families().size(), "family", "families")
                    + " (take an argument, e.g. {fact:home})"));
            CommandOutput.send(this, "Placeholders", fields,
                "Show one with " + pfx() + "ai placeholders <section>");
            return;
        }

        String match = findSection(grouped, section);
        if (match == null) {
            error("No section called '%s'. Sections: %s.", section, String.join(", ", grouped.keySet()));
            return;
        }
        java.util.List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (String name : grouped.get(match)) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("{" + name + "}",
                com.logvex.addon.common.Messages.truncate(
                    com.logvex.addon.assistant.core.ResponseTemplate.expand("{" + name + "}", ctx), 40)));
        }
        CommandOutput.send(this, match + " placeholders", fields,
            "Values are what they resolve to right now");
    }

    /** Resolves a typed section name case-insensitively, or null when there is no such section. */
    private static String findSection(java.util.Map<String, java.util.List<String>> grouped, String typed) {
        String needle = typed.trim().toLowerCase(Locale.ROOT);
        for (String name : grouped.keySet()) {
            if (name.toLowerCase(Locale.ROOT).equals(needle)) return name;
        }
        return null;
    }

    // ------------------------------------------------------------------ permissions

    private void permissions() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        com.logvex.addon.assistant.core.PermissionSet set = module.permissions();
        List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("State",
            set.enabled() ? "on" : "off (every command open)"));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Unlisted commands",
            set.defaultPolicy() == com.logvex.addon.assistant.core.PermissionSet.DefaultPolicy.CLOSED
                ? "local-only" : "open"));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Administrators",
            set.administrators().isEmpty() ? "none" : String.join(", ", set.administrators())));

        for (com.logvex.addon.assistant.core.CommandCatalog.Command command
            : com.logvex.addon.assistant.core.CommandCatalog.all()) {
            var level = set.effectiveLevel(command.name());
            var rule = set.rule(command.name()).orElse(null);
            String users = rule == null || rule.users().isEmpty()
                ? "" : " [" + String.join(", ", rule.users()) + "]";
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                command.name(), level.id() + users));
        }
        CommandOutput.sendList(this, "Assistant permissions", fields,
            set.enabled() ? "" : "turn them on with " + pfx() + "ai perms gui");
    }

    private void setPermission(String command, String level) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        var canonical = com.logvex.addon.assistant.core.CommandCatalog.canonical(command);
        if (canonical.isEmpty()) {
            warning("'%s' is not an assistant command.", command);
            return;
        }
        var parsed = com.logvex.addon.assistant.core.PermissionLevel.parse(level, null);
        if (parsed == null) {
            warning("Unknown level '%s'. Use everyone, whitelist, administrators or local-only.", level);
            return;
        }
        var existing = module.permissionFor(canonical.get())
            .orElse(com.logvex.addon.assistant.core.CommandPermission.open(canonical.get()));
        module.setPermission(existing.withLevel(parsed));
        info("%s is now %s.", canonical.get(), parsed.id());
    }

    private void allowUser(String command, String user) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        var canonical = com.logvex.addon.assistant.core.CommandCatalog.canonical(command);
        if (canonical.isEmpty()) {
            warning("'%s' is not an assistant command.", command);
            return;
        }
        if (module.addWhitelisted(canonical.get(), user)) {
            info("Whitelisted %s for %s.", user, canonical.get());
        } else {
            warning("'%s' is not a valid player name or name=uuid entry, or is already listed.", user);
        }
    }

    private void denyUser(String command, String user) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        var canonical = com.logvex.addon.assistant.core.CommandCatalog.canonical(command);
        if (canonical.isEmpty()) {
            warning("'%s' is not an assistant command.", command);
            return;
        }
        if (module.removeWhitelisted(canonical.get(), user)) {
            info("Removed %s from the whitelist for %s.", user, canonical.get());
        } else {
            warning("'%s' was not whitelisted for %s.", user, canonical.get());
        }
    }

    private void addAdmin(String user) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        if (module.addAdministrator(user)) {
            info("Added %s as an administrator.", user);
        } else {
            warning("'%s' is not a valid player name or name=uuid entry, or is already listed.", user);
        }
    }

    private void removeAdmin(String user) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        if (module.removeAdministrator(user)) {
            info("Removed %s from the administrators.", user);
        } else {
            warning("'%s' was not an administrator.", user);
        }
    }

    private void resetPermission(String command) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        var canonical = com.logvex.addon.assistant.core.CommandCatalog.canonical(command);
        if (canonical.isEmpty()) {
            warning("'%s' is not an assistant command.", command);
            return;
        }
        if (module.clearPermission(canonical.get())) {
            info("Cleared the rule for %s; it now uses the default level.", canonical.get());
        } else {
            warning("%s had no rule to clear.", canonical.get());
        }
    }

    /**
     * A one-line answer for a bare {@code !ai}.
     *
     * <p>The full block is ten fields, which is twelve chat lines: typing the command to see whether
     * the assistant is on should not bury the conversation. The detail is one explicit
     * {@code !ai status} away.
     */
    private void summary() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        info("%s: %s, %s, %s facts, %s handled. %sai status for detail.",
            module.config().name(),
            module.isActive() ? "on" : "off",
            Messages.plural(module.triggers().rules().size(), "trigger"),
            Messages.number(module.memory().facts().size()),
            Messages.number(module.runtime().handled()),
            pfx());
    }

    private void status() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        AssistantMemory memory = module.memory();
        List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Assistant",
            module.isActive() ? "on" : "off"));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Triggers",
            Messages.number(module.triggers().rules().size())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Facts",
            Messages.number(memory.facts().size())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Players known",
            Messages.number(memory.knownPlayers().size())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Handled",
            Messages.number(module.runtime().handled())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Ignored",
            Messages.number(module.runtime().ignored())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Rate limited",
            Messages.number(module.runtime().rateLimited())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Refused",
            Messages.number(module.runtime().denied())));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Chat seen",
            Messages.number(module.activity().seen()) + " lines from "
                + Messages.number(module.activity().activePlayers()) + " players"));
        fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field("Permissions",
            module.permissionsEnabled() ? "on" : "off"));
        CommandOutput.send(this, module.config().name(), fields, "");
    }

    /**
     * A one-line count for a bare {@code !ai triggers}.
     *
     * <p>The table is 27 rows and each row is a line, so printing it unasked floods chat. The full
     * list is {@code !ai triggers list}.
     */
    private void triggerSummary() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        List<TriggerRule> rules = module.triggers().rules();
        if (rules.isEmpty()) {
            warning("No triggers configured.");
            return;
        }
        long armed = rules.stream().filter(TriggerRule::enabled).count();
        info("%s, %s armed. %sai triggers list to show them.",
            Messages.plural(rules.size(), "trigger"), Messages.number(armed), pfx());
    }

    /**
     * Shows what the assistant can and cannot do right now.
     *
     * <p>Every row is derived from live state, so a disconnected client reports that it cannot see
     * TPS rather than printing a stale or invented figure.
     */
    private void capabilities() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        var caps = module.capabilities();
        List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (var ability : caps.abilities()) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                ability.name(), (ability.available() ? "yes - " : "no - ") + ability.detail()));
        }
        CommandOutput.send(this, "Capabilities", fields, caps.summary());
    }

    private void triggers() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        List<TriggerRule> rules = module.triggers().rules();
        if (rules.isEmpty()) {
            warning("No triggers configured.");
            return;
        }
        List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            TriggerRule rule = rules.get(i);
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                (i + 1) + ". " + rule.pattern(),
                (rule.reply().isBlank() ? "(no reply)" : rule.reply())
                    + " (" + rule.mode().name().toLowerCase(java.util.Locale.ROOT) + ")"));
        }
        CommandOutput.sendLong(this, Messages.plural(rules.size(), "trigger"), fields, "");
    }

    private void addTrigger(String pattern, String reply) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        if (module.addRule(TriggerRule.of(pattern, reply))) {
            info("Added trigger '%s'.", pattern);
        } else {
            warning("A trigger needs a non-blank pattern.");
        }
    }

    private void removeTrigger(int oneBased) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        int index = oneBased - 1;
        List<TriggerRule> rules = module.triggers().rules();
        if (index < 0 || index >= rules.size()) {
            warning("There is no trigger number %d.", oneBased);
            return;
        }
        String pattern = rules.get(index).pattern();
        module.removeRule(index);
        info("Removed trigger '%s'.", pattern);
    }

    private void facts() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        List<AssistantMemory.Fact> facts = module.memory().facts();
        if (facts.isEmpty()) {
            warning("No facts stored.");
            return;
        }
        List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (AssistantMemory.Fact fact : facts) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                fact.key(), Messages.truncate(fact.value(), 60)));
        }
        CommandOutput.sendLong(this, Messages.plural(facts.size(), "fact"), fields, "");
    }

    private void remember(String key, String value) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        if (!module.memory().rememberFact(key, value, System.currentTimeMillis())) {
            warning("A fact needs a non-blank name.");
            return;
        }
        module.saveMemory();
        info("Remembered %s = %s.", key, value);
    }

    private void forget(String key) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        boolean removed = module.memory().forgetFact(key);
        module.saveMemory();
        if (removed) info("Forgot %s.", key);
        else warning("No fact named %s.", key);
    }

    private void test(String message) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        AssistantBrain.Decision decision = module.runtime().brain().evaluate(
            "Tester", message, module.triggers(), module.config(), System.currentTimeMillis());
        if (!decision.acts()) {
            info("No trigger matches \"%s\".", Messages.truncate(message, 60));
            return;
        }
        info("Would %s: %s", decision.delivery().name().toLowerCase(java.util.Locale.ROOT),
            Messages.truncate(decision.text(), 120));
    }

    private void history() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        List<AssistantMemory.ConversationEntry> history = module.memory().history(10);
        if (history.isEmpty()) {
            warning("Nothing remembered yet.");
            return;
        }
        List<com.logvex.addon.assistant.core.ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (AssistantMemory.ConversationEntry entry : history) {
            fields.add(new com.logvex.addon.assistant.core.ReplyFormat.Field(
                Messages.clock(entry.timestamp()) + " " + Messages.player(entry.player()),
                Messages.truncate(entry.question(), 40) + " -> "
                    + Messages.truncate(entry.answer(), 40)));
        }
        CommandOutput.sendLong(this, "Recent exchanges", fields, "");
    }

    private static AssistantModule assistant() {
        return Modules.get().get(AssistantModule.class);
    }
}
