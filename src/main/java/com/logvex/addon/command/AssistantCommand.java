package com.logvex.addon.command;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.modules.AssistantModule;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.commands.Command;
import com.logvex.addon.framework.systems.modules.Modules;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandSource;

import java.util.List;

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
            status();
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
            triggers();
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

        builder.then(literal("model").executes(context -> {
            modelStatus();
            return SINGLE_SUCCESS;
        })
            .then(literal("status").executes(context -> {
                modelStatus();
                return SINGLE_SUCCESS;
            }))
            .then(literal("ask")
                .then(argument("message", StringArgumentType.greedyString()).executes(context -> {
                    modelAsk(StringArgumentType.getString(context, "message"));
                    return SINGLE_SUCCESS;
                })))
            .then(literal("teach")
                .then(argument("intent", StringArgumentType.word())
                    .then(argument("example", StringArgumentType.greedyString()).executes(context -> {
                        modelTeach(StringArgumentType.getString(context, "intent"),
                            StringArgumentType.getString(context, "example"));
                        return SINGLE_SUCCESS;
                    }))))
            .then(literal("save").executes(context -> {
                AssistantModule module = assistant();
                if (module == null) {
                    error("The assistant module is not available.");
                    return SINGLE_SUCCESS;
                }
                module.saveAi();
                info("Local AI model saved.");
                return SINGLE_SUCCESS;
            })));
    }

    private void help() {
        info("Assistant commands:");
        info("  (highlight).ai status(default) - assistant state and memory summary.");
        info("  (highlight).ai gui(default) - open the assistant manager.");
        info("  (highlight).ai triggers [list|add <pattern> <reply>|remove <n>|restore](default).");
        info("  (highlight).ai facts(default) - list remembered facts.");
        info("  (highlight).ai remember <key> <value>(default) - store a fact.");
        info("  (highlight).ai forget <key>(default) - remove a fact.");
        info("  (highlight).ai test <message>(default) - see what the assistant would do.");
        info("  (highlight).ai history(default) - recent remembered exchanges.");
        info("  (highlight).ai model [status|ask <text>|teach <intent> <example>|save](default).");
        info("  (highlight).ai clear(default) - clear all memory.");
    }

    private void modelStatus() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        com.logvex.addon.ai.core.AiEngine engine = module.aiEngine();
        info("Local AI: %s, %d%% confidence, %s intents",
            engine.enabled() ? "on" : "off",
            Math.round(engine.threshold() * 100),
            Messages.number(engine.model().intentCount()));
        info("  Classified %s, understood %s (%s%%), cache hits %s",
            Messages.number(engine.classified()),
            Messages.number(engine.understood()),
            Messages.number(Math.round(engine.understandingRate() * 100)),
            Messages.number(engine.cacheHits()));
        info("  Intents: %s", String.join(", ", engine.model().intentNames()));
    }

    private void modelAsk(String message) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        com.logvex.addon.ai.core.AiEngine engine = module.aiEngine();
        String intent = engine.intentOf(message);
        if (intent == null) {
            warning("The model does not understand \"%s\".", Messages.truncate(message, 60));
            return;
        }
        String reply = engine.respond(message, "Tester", java.util.Map.of(), System.currentTimeMillis());
        info("Intent '%s' (%.0f%%): %s", intent, engine.confidence(message) * 100,
            Messages.truncate(reply, 120));
    }

    private void modelTeach(String intent, String example) {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        if (module.aiEngine().model().teach(intent, example)) {
            module.saveAi();
            info("Taught '%s' the phrase \"%s\".", intent, Messages.truncate(example, 60));
        } else {
            warning("Could not teach that; the model may be full.");
        }
    }

    private void status() {
        AssistantModule module = assistant();
        if (module == null) {
            error("The assistant module is not available.");
            return;
        }
        AssistantMemory memory = module.memory();
        info("%s: %s", module.config().name(), module.isActive() ? "on" : "off");
        info("  Triggers: %d, facts: %d, players known: %d",
            module.triggers().rules().size(), memory.facts().size(), memory.knownPlayers().size());
        info("  Handled %s, ignored %s, rate limited %s",
            Messages.number(module.runtime().handled()),
            Messages.number(module.runtime().ignored()),
            Messages.number(module.runtime().rateLimited()));
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
        info("%s:", Messages.plural(rules.size(), "trigger"));
        for (int i = 0; i < rules.size(); i++) {
            TriggerRule rule = rules.get(i);
            info("  %d. %s -> %s (%s)", i + 1, rule.pattern(),
                rule.reply().isBlank() ? "(no reply)" : rule.reply(),
                rule.mode().name().toLowerCase(java.util.Locale.ROOT));
        }
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
        info("%s:", Messages.plural(facts.size(), "fact"));
        for (AssistantMemory.Fact fact : facts) {
            info("  %s = %s", fact.key(), Messages.truncate(fact.value(), 60));
        }
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
        info("Recent exchanges:");
        for (AssistantMemory.ConversationEntry entry : history) {
            info("  %s %s: %s -> %s", Messages.clock(entry.timestamp()),
                Messages.player(entry.player()), Messages.truncate(entry.question(), 40),
                Messages.truncate(entry.answer(), 40));
        }
    }

    private static AssistantModule assistant() {
        return Modules.get().get(AssistantModule.class);
    }
}
