package com.logvex.addon.assistant.core;

import java.util.List;
import java.util.Locale;

/**
 * The assistant's decision logic, free of Minecraft types.
 *
 * <p>Given a chat line it decides whether the assistant should do anything and, if so, what. It
 * never sends anything itself: it returns a {@link Decision} that the Minecraft-facing module acts
 * on. This keeps the interesting logic unit-testable and keeps every side effect (chat, sound,
 * notifications, memory writes) in one place.
 *
 * <p>Two things can happen for a message: a configured keyword {@link TriggerRule} matches, or the
 * message is a memory command ({@code !remember}, {@code !forget}, {@code !fact}, ...). A trigger
 * takes precedence so a user's explicit rule always wins over the built-in commands.
 */
public final class AssistantBrain {
    /** How a decision should be delivered. */
    public enum Delivery {
        /** Say the text in public chat. */
        CHAT,
        /** Raise a local notification only. */
        NOTIFY,
        /** Do nothing visible (memory bookkeeping only). */
        SILENT,
        /** Nothing to do. */
        NONE
    }

    /**
     * The outcome of evaluating one message.
     *
     * @param delivery   how to deliver {@code text}
     * @param text       the message to deliver, already template-expanded
     * @param rule       the trigger that fired, or null for a memory command
     * @param sound      whether the rule asked for a sound
     * @param remember   whether the exchange should be written to memory
     * @param command    true when the decision came from a memory command rather than a trigger
     */
    public record Decision(Delivery delivery, String text, TriggerRule rule,
                           boolean sound, boolean remember, boolean command) {
        public static final Decision NONE = new Decision(Delivery.NONE, "", null, false, false, false);

        public boolean acts() {
            return delivery != Delivery.NONE;
        }
    }

    private final AssistantMemory memory;

    public AssistantBrain(AssistantMemory memory) {
        this.memory = memory == null ? new AssistantMemory() : memory;
    }

    public AssistantMemory memory() {
        return memory;
    }

    /**
     * Evaluates one incoming message against {@code triggers}.
     *
     * @param sender   the player the message came from, or an empty string when unknown
     * @param content  the message text
     * @param triggers the rules to match, already filtered
     * @param config   the current configuration
     * @param now      the current time in epoch milliseconds
     */
    public Decision evaluate(String sender, String content, TriggerSet triggers,
                             AssistantConfig config, long now) {
        if (content == null || content.isBlank()) return Decision.NONE;
        AssistantConfig cfg = config == null ? AssistantConfig.DEFAULTS : config;
        TriggerSet set = triggers == null ? TriggerSet.empty() : triggers;

        if (cfg.respondToTriggers()) {
            TriggerRule rule = set.firstMatch(sender, content);
            if (rule != null) return fromTrigger(rule, sender, content, cfg, now);
        }
        if (cfg.memoryCommands()) {
            Decision command = fromCommand(sender, content, cfg, now);
            if (command != null) return command;
        }
        return Decision.NONE;
    }

    // ------------------------------------------------------------------ triggers

    private Decision fromTrigger(TriggerRule rule, String sender, String content,
                                 AssistantConfig config, long now) {
        ResponseTemplate.Context context = new ResponseTemplate.Context(
            sender, content, "", memory, now);
        String text = ResponseTemplate.expand(rule.reply(), context);
        text = clamp(text, config.maxReplyLength());

        Delivery delivery = switch (rule.mode()) {
            case CHAT -> Delivery.CHAT;
            case NOTIFY -> Delivery.NOTIFY;
            case SILENT -> Delivery.SILENT;
        };
        if (text.isBlank() && delivery == Delivery.CHAT) delivery = Delivery.SILENT;

        boolean remember = rule.remember() || config.rememberConversations();
        if (remember) {
            memory.recordConversation(sender, content, text, now);
            memory.incrementCounter("replies", 1);
        }
        return new Decision(delivery, text, rule, rule.sound(), remember, false);
    }

    // ------------------------------------------------------------------ memory commands

    /**
     * Parses a memory command. Returns null when {@code content} is not a command the assistant
     * understands, so the caller can fall through to no-op.
     */
    private Decision fromCommand(String sender, String content, AssistantConfig config, long now) {
        String prefix = config.commandPrefix();
        String trimmed = content.strip();
        if (!trimmed.startsWith(prefix)) return null;
        String body = trimmed.substring(prefix.length()).strip();
        if (body.isEmpty()) return null;

        String[] parts = body.split("\\s+", 3);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? body.substring(verb.length()).strip() : "";

        return switch (verb) {
            case "remember" -> commandRemember(sender, rest, config, now);
            case "forget" -> commandForget(sender, rest, config, now);
            case "fact" -> commandFact(sender, rest, config);
            case "facts" -> commandFacts(config);
            case "memory" -> commandMemory(sender, config);
            case "forgetme" -> commandForgetMe(sender, config, now);
            case "history" -> commandHistory(config);
            case "whoami" -> commandWhoAmI(sender, config);
            default -> null;
        };
    }

    private Decision commandRemember(String sender, String rest, AssistantConfig config, long now) {
        int eq = rest.indexOf('=');
        if (eq < 0) {
            return reply(config.memoryReplyMode(), "Usage: " + config.commandPrefix() + "remember key = value", false);
        }
        String key = rest.substring(0, eq).strip();
        String value = rest.substring(eq + 1).strip();
        if (key.isEmpty()) {
            return reply(config.memoryReplyMode(), "The fact needs a name.", false);
        }
        memory.rememberFact(key, value, now);
        memory.incrementCounter("facts-stored", 1);
        if (config.rememberConversations()) {
            memory.recordConversation(sender, rest, "remembered " + key, now);
        }
        return reply(config.memoryReplyMode(),
            "Remembered " + key + " = " + value + ".", true);
    }

    private Decision commandForget(String sender, String rest, AssistantConfig config, long now) {
        String key = rest.strip();
        if (key.isEmpty()) {
            return reply(config.memoryReplyMode(), "Usage: " + config.commandPrefix() + "forget key", false);
        }
        boolean removed = memory.forgetFact(key);
        if (removed && config.rememberConversations()) {
            memory.recordConversation(sender, rest, "forgot " + key, now);
        }
        return reply(config.memoryReplyMode(),
            removed ? "Forgot " + key + "." : "I have no fact named " + key + ".", true);
    }

    private Decision commandFact(String sender, String rest, AssistantConfig config) {
        String key = rest.strip();
        if (key.isEmpty()) {
            return reply(config.memoryReplyMode(), "Usage: " + config.commandPrefix() + "fact key", false);
        }
        String value = memory.fact(key).map(AssistantMemory.Fact::value).orElse(null);
        if (value == null) {
            return reply(config.memoryReplyMode(), "I do not know " + key + ".", false);
        }
        return reply(config.memoryReplyMode(), key + " = " + value, false);
    }

    private Decision commandFacts(AssistantConfig config) {
        List<AssistantMemory.Fact> facts = memory.facts();
        if (facts.isEmpty()) {
            return reply(config.memoryReplyMode(), "I have no stored facts.", false);
        }
        StringBuilder sb = new StringBuilder("I know " + facts.size() + " fact"
            + (facts.size() == 1 ? "" : "s") + ": ");
        int shown = 0;
        for (AssistantMemory.Fact fact : facts) {
            if (shown == 8) {
                sb.append(", ...");
                break;
            }
            if (shown > 0) sb.append(", ");
            sb.append(fact.key()).append('=').append(clamp(fact.value(), 24));
            shown++;
        }
        return reply(config.memoryReplyMode(), sb.toString(), false);
    }

    private Decision commandMemory(String sender, AssistantConfig config) {
        String summary = memory.facts().size() + " facts, " + memory.knownPlayers().size() + " players, "
            + memory.historySize() + " remembered lines";
        return reply(config.memoryReplyMode(), summary, false);
    }

    private Decision commandForgetMe(String sender, AssistantConfig config, long now) {
        if (sender == null || sender.isBlank()) {
            return reply(config.memoryReplyMode(), "I do not know who you are.", false);
        }
        boolean removed = memory.forgetPlayer(sender);
        if (removed && config.rememberConversations()) {
            memory.recordConversation(sender, "forgetme", "cleared", now);
        }
        return reply(config.memoryReplyMode(),
            removed ? "Forgot everything about " + sender + "." : "I had nothing stored about you.", true);
    }

    private Decision commandHistory(AssistantConfig config) {
        List<AssistantMemory.ConversationEntry> recent = memory.history(5);
        if (recent.isEmpty()) {
            return reply(config.memoryReplyMode(), "Nothing remembered yet.", false);
        }
        StringBuilder sb = new StringBuilder("Recent: ");
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(clamp(recent.get(i).question(), 30));
        }
        return reply(config.memoryReplyMode(), sb.toString(), false);
    }

    private Decision commandWhoAmI(String sender, AssistantConfig config) {
        String name = sender == null || sender.isBlank() ? "someone" : sender;
        List<AssistantMemory.Fact> perPlayer = memory.playerFacts(name);
        return reply(config.memoryReplyMode(),
            "You are " + name + (perPlayer.isEmpty() ? "." : ", and I know " + perPlayer.size()
                + " thing" + (perPlayer.size() == 1 ? "" : "s") + " about you."), false);
    }

    private Decision reply(TriggerRule.ReplyMode mode, String text, boolean remember) {
        Delivery delivery = switch (mode) {
            case CHAT -> Delivery.CHAT;
            case NOTIFY -> Delivery.NOTIFY;
            case SILENT -> Delivery.SILENT;
        };
        return new Decision(delivery, text, null, false, remember, true);
    }

    private static String clamp(String text, int max) {
        if (text == null) return "";
        if (max <= 0 || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    /** A one-line status for the HUD and commands. */
    public String statusLine() {
        return memory.facts().size() + " facts";
    }
}
