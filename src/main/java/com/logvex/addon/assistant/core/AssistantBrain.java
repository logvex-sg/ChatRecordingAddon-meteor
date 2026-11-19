package com.logvex.addon.assistant.core;

import com.logvex.addon.ai.core.AiEngine;
import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommand;
import com.logvex.addon.stats.StatusCommands;
import com.logvex.addon.stats.StatusTemplate;

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
     * @param denied     true when the permission gate refused the command; nothing was executed
     * @param embed      a structured block to deliver instead of {@code text}, or null
     */
    public record Decision(Delivery delivery, String text, TriggerRule rule,
                           boolean sound, boolean remember, boolean command, boolean denied,
                           ReplyFormat.Embed embed, String report, String statusCard) {
        public static final Decision NONE =
            new Decision(Delivery.NONE, "", null, false, false, false, false, null, null, null);

        public Decision(Delivery delivery, String text, TriggerRule rule,
                        boolean sound, boolean remember, boolean command, boolean denied,
                        ReplyFormat.Embed embed, String report) {
            this(delivery, text, rule, sound, remember, command, denied, embed, report, null);
        }

        public Decision(Delivery delivery, String text, TriggerRule rule,
                        boolean sound, boolean remember, boolean command, boolean denied,
                        ReplyFormat.Embed embed) {
            this(delivery, text, rule, sound, remember, command, denied, embed, null, null);
        }

        public Decision(Delivery delivery, String text, TriggerRule rule,
                        boolean sound, boolean remember, boolean command, boolean denied) {
            this(delivery, text, rule, sound, remember, command, denied, null, null, null);
        }

        /** The original six-field form, for every decision that was not a refusal. */
        public Decision(Delivery delivery, String text, TriggerRule rule,
                        boolean sound, boolean remember, boolean command) {
            this(delivery, text, rule, sound, remember, command, false, null, null, null);
        }

        /**
         * The status command this decision answers, or null.
         *
         * <p>Separate from {@link #report} because the two are delivered differently: a report is a
         * message to the public webhook, while a status card is a local readout the caller may
         * mirror to the admin webhook. Overloading one field for both would make an overview post a
         * public report.
         */
        public boolean hasStatusCard() {
            return statusCard != null && !statusCard.isBlank();
        }

        /**
         * True when this decision also asks the caller to post a report to the webhook.
         *
         * <p>The reason travels with the decision instead of being sent from here, so the brain stays
         * free of network and settings code while the permission check that authorised the report
         * still happens in the one place every other command is checked.
         */
        public boolean hasReport() {
            return report != null && !report.isBlank();
        }

        public boolean acts() {
            return delivery != Delivery.NONE;
        }

        /** True when this decision carries a block rather than a single line. */
        public boolean hasEmbed() {
            return embed != null;
        }

        /** The lines to deliver at {@code width}, keeping each block's own layout. */
        public java.util.List<String> lines(int width) {
            return lines(width, null);
        }

        /**
         * The lines to deliver, in order: the rendered block when there is one, otherwise the single
         * text line.
         *
         * @param style the layout to force, or null to keep the one the block was written for
         */
        public java.util.List<String> lines(int width, ReplyFormat.Style style) {
            if (embed == null) return text == null || text.isBlank() ? java.util.List.of()
                : java.util.List.of(text);
            return ReplyFormat.render(style == null ? embed : withStyle(embed, style), width);
        }

        private static ReplyFormat.Embed withStyle(ReplyFormat.Embed embed, ReplyFormat.Style style) {
            return new ReplyFormat.Embed(embed.title(), embed.fields(), embed.footer(), style);
        }

        /** A refusal: delivered to the speaker, but nothing was executed. */
        public static Decision refusal(TriggerRule.ReplyMode mode, String reason) {
            Delivery delivery = switch (mode) {
                case CHAT -> Delivery.CHAT;
                case NOTIFY -> Delivery.NOTIFY;
                case SILENT -> Delivery.SILENT;
            };
            return new Decision(delivery, reason, null, false, false, true, true);
        }
    }

    private final AssistantMemory memory;
    private AiEngine engine;

    /** The configured greeting pools, keyed by bucket id; null means the built-in defaults. */
    private java.util.Map<String, String> greetingPools;
    private String goodGameReply = DEFAULT_GOOD_GAME_REPLY;

    /** The macro table, so {@code !macros} and {@code !macrohelp} can describe it. */
    private MacroSet macros = MacroSet.EMPTY;

    /**
     * Per-command configuration for the non-status prefix commands.
     *
     * <p>Null means "use the shipped defaults", which is what a caller that has no settings passes.
     */
    private java.util.Map<String, CommandDefaults.Entry> commandDefaults;

    public AssistantBrain(AssistantMemory memory) {
        this.memory = memory == null ? new AssistantMemory() : memory;
    }

    /** Replaces the macro table the help commands read. */
    public void setMacros(MacroSet macros) {
        this.macros = macros == null ? MacroSet.EMPTY : macros;
    }

    /** Replaces the per-command table; null restores the shipped defaults. */
    public void setCommandDefaults(java.util.Map<String, CommandDefaults.Entry> entries) {
        this.commandDefaults = entries;
    }

    /** The configured entry for {@code command}, or the shipped one, never null. */
    private CommandDefaults.Entry commandEntry(String command) {
        if (commandDefaults != null) {
            CommandDefaults.Entry configured = commandDefaults.get(command);
            if (configured != null) return configured;
        }
        return CommandDefaults.defaults().getOrDefault(command,
            new CommandDefaults.Entry(command, true, CommandDefaults.defaultOutput(command), ""));
    }

    public MacroSet macros() {
        return macros;
    }

    public AssistantMemory memory() {
        return memory;
    }

    /** The default answer to the good-game command. */
    public static final String DEFAULT_GOOD_GAME_REPLY = "Good game, {player}!";

    /** Replaces the greeting pools the hello command draws from. */
    public void setGreetingPools(java.util.Map<String, String> pools) {
        this.greetingPools = pools;
    }

    /** Replaces the good-game reply. A blank value restores the default. */
    public void setGoodGameReply(String reply) {
        this.goodGameReply = reply == null || reply.isBlank()
            ? DEFAULT_GOOD_GAME_REPLY : reply;
    }

    /** Attaches the local AI. Without one the brain only answers triggers and memory commands. */
    public void setEngine(AiEngine engine) {
        this.engine = engine;
    }

    /**
     * Evaluates one incoming message, trying every source in priority order.
     *
     * <p>Kept for callers that want the whole decision in one call (and for tests). The live runtime
     * uses the granular {@link #trigger}, {@link #command} and {@link #ai} methods instead, because
     * each source has its own cooldown and rate-limit treatment.
     */
    public Decision evaluate(String sender, String content, TriggerSet triggers,
                             AssistantConfig config, long now) {
        if (content == null || content.isBlank()) return Decision.NONE;
        AssistantConfig cfg = config == null ? AssistantConfig.DEFAULTS : config;
        TriggerSet set = triggers == null ? TriggerSet.empty() : triggers;

        if (cfg.respondToTriggers()) {
            TriggerRule rule = set.firstMatch(sender, content);
            if (rule != null) return trigger(rule, sender, content, cfg, now);
        }
        if (cfg.memoryCommands()) {
            Decision command = command(sender, content, cfg, now);
            if (command != null) return command;
        }
        if (cfg.localAi()) {
            Decision ai = ai(sender, content, cfg, now);
            if (ai != null) return ai;
        }
        return Decision.NONE;
    }

    /** The decision for an already-matched trigger rule. */
    public Decision trigger(TriggerRule rule, String sender, String content,
                            AssistantConfig config, long now) {
        return fromTrigger(rule, sender, content, config, now, ServerStats.OFFLINE);
    }

    /** The decision for an already-matched trigger rule, with live figures for its template. */
    public Decision trigger(TriggerRule rule, String sender, String content,
                            AssistantConfig config, long now, ServerStats stats) {
        return fromTrigger(rule, sender, content, config, now, stats);
    }

    /** The decision for a memory command, or null when {@code content} is not one. */
    public Decision command(String sender, String content, AssistantConfig config, long now) {
        return fromCommand(sender, content, config, now);
    }

    /**
     * The decision for a memory or stats command, or null when {@code content} is neither.
     *
     * <p>This is the form the live runtime uses: it carries the current {@link ServerStats} and the
     * stats settings, so {@code !ping}, {@code !tps} and the rest can answer. The overload without
     * them keeps the original behaviour for callers that only need memory commands.
     */
    public Decision command(String sender, String content, AssistantConfig config, long now,
                            ServerStats stats, StatusCommands.Settings statusSettings) {
        return fromCommand(sender, content, config, now, stats, statusSettings);
    }

    /**
     * The permission-checked form the live runtime uses.
     *
     * @param identity    the sender as the client verified them
     * @param permissions the current rules; a null set means permissions are off
     * @param activity    where to record the command, or null for a throwaway
     */
    public Decision command(SenderIdentity identity, String content, AssistantConfig config,
                            long now, ServerStats stats, StatusCommands.Settings statusSettings,
                            PermissionSet permissions, ChatActivity activity) {
        return command(identity, content, config, now, stats, statusSettings, permissions, activity,
            true);
    }

    /**
     * As above, with the help switch.
     *
     * @param helpEnabled false makes the help command answer nothing, so a user can hide the
     *                    command list without restricting it for everyone else
     */
    public Decision command(SenderIdentity identity, String content, AssistantConfig config,
                            long now, ServerStats stats, StatusCommands.Settings statusSettings,
                            PermissionSet permissions, ChatActivity activity, boolean helpEnabled) {
        return command(identity, content, config, now, stats, statusSettings, permissions, activity,
            helpEnabled, -1);
    }

    /**
     * The full form, with the requester's own latency.
     *
     * <p>The ping figure belongs to the person who asked, and only the caller can read it from the
     * tab list. Passing it here keeps the brain free of Minecraft types while still letting
     * {@code !ping} answer about the speaker rather than about this client.
     *
     * @param requesterPing the speaker's latency in milliseconds, or a negative value when unknown
     */
    public Decision command(SenderIdentity identity, String content, AssistantConfig config,
                            long now, ServerStats stats, StatusCommands.Settings statusSettings,
                            PermissionSet permissions, ChatActivity activity, boolean helpEnabled,
                            int requesterPing) {
        if (!helpEnabled && isHelpRequest(content, config)) return Decision.NONE;
        return fromCommand(identity, content, config, now, stats, statusSettings, permissions,
            activity, requesterPing);
    }

    /** True when {@code content} is the help command or one of its aliases. */
    private static boolean isHelpRequest(String content, AssistantConfig config) {
        if (content == null || config == null) return false;
        String trimmed = content.strip();
        String prefix = config.commandPrefix();
        if (!trimmed.startsWith(prefix)) return false;
        String body = trimmed.substring(prefix.length()).strip();
        if (body.isEmpty()) return false;
        String word = body.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return CommandCatalog.canonical(word).filter("help"::equals).isPresent();
    }

    /** Asks the local AI for a reply, or null when it declines. See {@link #fromAi}. */
    public Decision ai(String sender, String content, AssistantConfig config, long now) {
        return fromAi(sender, content, config, now, ServerStats.OFFLINE);
    }

    /** Asks the local AI for a reply, expanding placeholders in it against the live figures. */
    public Decision ai(String sender, String content, AssistantConfig config, long now,
                       ServerStats stats) {
        return fromAi(sender, content, config, now, stats);
    }

    /**
     * Asks the local AI for a reply.
     *
     * <p>Returns null when the AI is not attached, is not confident enough, or produced nothing, so
     * the caller falls through to no-op. The reply mode decides whether it is spoken, notified or
     * kept silent; a chat reply that ends up empty is downgraded to silent rather than sent blank.
     */
    private Decision fromAi(String sender, String content, AssistantConfig config, long now,
                            ServerStats stats) {
        if (engine == null || !engine.enabled()) return null;

        java.util.Map<String, String> context = new java.util.HashMap<>(4);
        context.put("name", config.name());
        context.put("prefix", config.commandPrefix());
        context.put("factcount", Integer.toString(memory.facts().size()));
        context.put("time", java.time.LocalTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        // Memory is consulted before the classifier. A stored fact is exact, user-authored and
        // specific to this install, so it beats a guessed intent: if the user taught the assistant
        // what "prefix" means, answering from the corpus instead would ignore their own data.
        String text = recallReply(sender, content);
        if (text == null) text = engine.respond(content, sender, context, now);
        if (text == null || text.isBlank()) return null;
        text = ResponseTemplate.expand(text, new ResponseTemplate.Context(
            sender, content, stats.server(), memory, now, stats));
        text = clamp(text, config.maxReplyLength());

        Delivery delivery = switch (config.aiReplyMode()) {
            case CHAT -> Delivery.CHAT;
            case NOTIFY -> Delivery.NOTIFY;
            case SILENT -> Delivery.SILENT;
        };
        boolean remember = config.rememberConversations();
        if (remember) {
            memory.recordConversation(sender, content, text, now);
            memory.incrementCounter("ai-replies", 1);
        }
        return new Decision(delivery, text, null, false, remember, false);
    }

    /**
     * Answers from a stored fact when the line names one, or null to let the classifier decide.
     *
     * <p>Only a question-shaped line is answered this way. A bare mention is not a request: "the
     * server lagged" must not make the assistant recite the server fact, so the line has to read as
     * a question before memory is used. That keeps recall from turning every keyword in normal chat
     * into an unsolicited lecture.
     *
     * <p>The reply names the fact it used, because an answer the user cannot trace back to their own
     * stored text is one they cannot correct.
     */
    private String recallReply(String sender, String content) {
        if (!looksLikeQuestion(content)) return null;
        java.util.Optional<AssistantMemory.Fact> found = memory.recall(content);
        if (found.isEmpty()) return null;
        AssistantMemory.Fact fact = found.get();
        if (fact.value() == null || fact.value().isBlank()) return null;
        memory.incrementCounter("recalls", 1);
        return fact.key() + ": " + fact.value();
    }

    /**
     * True when {@code content} is phrased as a question.
     *
     * <p>A question mark or an interrogative opener is enough. This is deliberately shallow: it is a
     * gate on using memory, not an attempt to parse English, and a false positive costs only a
     * slightly eager answer while a false negative costs a missed one.
     */
    private static boolean looksLikeQuestion(String content) {
        if (content == null) return false;
        String text = content.strip().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) return false;
        if (text.indexOf('?') >= 0) return true;
        for (String opener : QUESTION_OPENERS) {
            if (text.startsWith(opener + " ")) return true;
        }
        return false;
    }

    /** Openers that make a line a question even without a question mark. */
    private static final String[] QUESTION_OPENERS = {
        "what", "whats", "where", "when", "who", "why", "how", "which", "is", "are", "can",
        "could", "do", "does", "did", "tell me", "explain", "any idea"
    };

    // ------------------------------------------------------------------ triggers

    private Decision fromTrigger(TriggerRule rule, String sender, String content,
                                 AssistantConfig config, long now, ServerStats stats) {
        ResponseTemplate.Context context = new ResponseTemplate.Context(
            sender, content, stats.server(), memory, now, stats);
        String text = ResponseTemplate.expand(rule.chosenReply(null), context);
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
     * Parses a command. Returns null when {@code content} is not a command the assistant
     * understands, so the caller can fall through to no-op.
     */
    private Decision fromCommand(String sender, String content, AssistantConfig config, long now) {
        return fromCommand(SenderIdentity.unverified(sender), content, config, now,
            ServerStats.OFFLINE, null, null, null);
    }

    private Decision fromCommand(String sender, String content, AssistantConfig config, long now,
                                 ServerStats stats, StatusCommands.Settings statusSettings) {
        return fromCommand(SenderIdentity.unverified(sender), content, config, now, stats,
            statusSettings, null, null);
    }

    /**
     * Parses and authorises one command.
     *
     * <p>Order is the whole point of this method, and it must not be rearranged:
     * <ol>
     *   <li>the verb is resolved to a canonical command through the alias table, so every spelling
     *       of a restricted command reaches the same rule;</li>
     *   <li>the permission check runs — <em>before</em> any memory write, any reply, any AI call;</li>
     *   <li>only then does the command body execute.</li>
     * </ol>
     * A denied command returns a refusal with no side effect at all, so a rejected
     * {@code !remember} cannot leave a fact behind.
     *
     * @param identity the sender as the client can verify them; never a name taken from the message
     */
    private Decision fromCommand(SenderIdentity identity, String content, AssistantConfig config,
                                 long now, ServerStats stats, StatusCommands.Settings statusSettings,
                                 PermissionSet permissions, ChatActivity activity) {
        return fromCommand(identity, content, config, now, stats, statusSettings, permissions,
            activity, -1);
    }

    private Decision fromCommand(SenderIdentity identity, String content, AssistantConfig config,
                                 long now, ServerStats stats, StatusCommands.Settings statusSettings,
                                 PermissionSet permissions, ChatActivity activity,
                                 int requesterPing) {
        String prefix = config.commandPrefix();
        String trimmed = content.strip();
        if (!trimmed.startsWith(prefix)) return null;
        String body = trimmed.substring(prefix.length()).strip();
        if (body.isEmpty()) return null;

        String[] parts = body.split("\\s+", 3);
        String word = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? body.substring(verbLength(body)).strip() : "";

        // A configured stats verb is an alias for its figure, so renaming `ping` to `latency` does
        // not create a second, unchecked command.
        String canonical = CommandCatalog.canonical(word).orElse(null);
        if (canonical == null) canonical = statsCanonical(word, statusSettings);
        if (canonical == null) return null;

        PermissionSet perms = permissions == null ? PermissionSet.permissive() : permissions;
        PermissionSet.Verdict verdict = perms.permits(canonical, identity);
        if (!verdict.allowed()) {
            // Refused without executing: no memory write, no AI call, no reply beyond the reason.
            return Decision.refusal(config.memoryReplyMode(), verdict.reason());
        }

        ChatActivity observed = activity == null ? new ChatActivity() : activity;
        Decision decision = dispatch(identity, canonical, word, rest, config, now, stats,
            statusSettings, observed, perms, requesterPing);
        decision = applyCommandDefaults(canonical, identity, rest, decision, now, stats);
        if (decision == null) return null;
        // Only a command that actually ran is counted; a refusal is recorded separately so
        // `!activity` cannot report a command as used when the gate turned it away.
        if (decision.acts() && !decision.denied()) observed.recordCommand(canonical);
        return decision;
    }

    /**
     * Applies the per-command table to an answer that has already been produced.
     *
     * <p>Applied after dispatch so the table can govern every command without each one having to
     * consult it: the command still decides <em>what</em> it would say, and this decides whether it
     * may say it and where it goes. A custom template replaces the wording entirely.
     *
     * <p>Status commands are left alone. They already have a table of their own that models output
     * and wording together, and running a second table over them would give one command two
     * competing configurations with no rule for which wins.
     */
    private Decision applyCommandDefaults(String canonical, SenderIdentity identity, String rest,
                                          Decision decision, long now, ServerStats stats) {
        if (StatusCommand.find(canonical).isPresent()) return decision;
        CommandDefaults.Entry entry = commandEntry(canonical);
        if (!entry.enabled()) return Decision.NONE;
        if (decision == null || decision.denied() || decision.hasReport()) return decision;

        String text = entry.customised()
            ? ResponseTemplate.expand(entry.template(), new ResponseTemplate.Context(
                identity.name(), rest, stats.server(), memory, now, stats))
            : decision.text();
        Delivery delivery = switch (entry.output()) {
            case CHAT -> Delivery.CHAT;
            case LOCAL -> Delivery.NOTIFY;
            case SILENT -> Delivery.SILENT;
        };
        if (delivery == decision.delivery() && !entry.customised()) return decision;
        return new Decision(delivery, text, decision.rule(), decision.sound(), decision.remember(),
            decision.command(), decision.denied(), decision.embed(), decision.report(),
            decision.statusCard());
    }

    /** The canonical command a configured stats verb refers to, or null. */
    private static String statsCanonical(String word, StatusCommands.Settings options) {
        if (options == null) return null;
        for (java.util.Map.Entry<String, String> entry : options.verbs().entrySet()) {
            if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(word)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The length of the first word in {@code body}, so the remainder can be sliced safely. */
    private static int verbLength(String body) {
        for (int i = 0; i < body.length(); i++) {
            if (Character.isWhitespace(body.charAt(i))) return i;
        }
        return body.length();
    }

    /** Runs an authorised command. */
    private Decision dispatch(SenderIdentity identity, String canonical, String word, String rest,
                              AssistantConfig config, long now, ServerStats stats,
                              StatusCommands.Settings statusSettings, ChatActivity activity,
                              PermissionSet perms, int requesterPing) {
        String sender = identity.name();
        return switch (canonical) {
            case "help" -> commandHelp(identity, config, rest, statusSettings, perms);
            case "about" -> commandAbout(config, activity, perms);
            case "activity" -> commandActivity(config, activity, now);
            case "remember" -> commandRemember(sender, rest, config, now);
            case "forget" -> commandForget(sender, rest, config, now);
            case "fact" -> commandFact(sender, rest, config);
            case "facts" -> commandFacts(config);
            case "memories" -> commandMemories(sender, config);
            case "forgetme" -> commandForgetMe(sender, config, now);
            case "history" -> commandHistory(config);
            case "hello" -> commandHello(sender, config, now, stats);
            case "gg" -> commandGoodGame(sender, config, now, stats);
            case "whoami" -> commandWhoAmI(sender, config);
            case "macros" -> commandMacros(config);
            case "macrohelp" -> commandMacroHelp(rest, config);
            case "mod", "report" -> commandReport(sender, canonical, rest, config);
            case "who" -> commandWho(config, stats);
            case "stats" -> commandStats(config, stats);
            default -> statusCommand(identity, canonical, word, config, stats, statusSettings,
                requesterPing);
        };
    }

    /**
     * Answers one live status command, honouring its configured output mode.
     *
     * <p>The mode is the whole point of this path. {@code LOCAL} produces a notification this client
     * shows and never transmits; {@code CHAT} produces exactly one ordinary chat message. Nothing
     * here can reach a server command, so no status answer can be broadcast by accident.
     */
    private Decision statusCommand(SenderIdentity identity, String canonical, String word,
                                   AssistantConfig config, ServerStats stats,
                                   StatusCommands.Settings settings, int requesterPing) {
        ServerStats live = stats == null ? ServerStats.OFFLINE : stats;
        // Only the local player's own figure is this client's reading; for anyone else the tab list
        // is the only source, and the caller passes it in. A negative value means "not known".
        int ping = requesterPing >= 0 ? requesterPing
            : (identity.local() ? live.latency() : -1);
        StatusTemplate.Context context = new StatusTemplate.Context(identity.name(), ping);
        java.util.Optional<StatusCommands.Answer> answer =
            StatusCommands.answer(canonical, live, settings, context);
        if (answer.isEmpty() && !word.equals(canonical)) {
            answer = StatusCommands.answer(word, live, settings, context);
        }
        if (answer.isEmpty()) return null;
        // A command that describes the player is answered for the local player only. The output is
        // already forced local, so nothing would leak; refusing remote senders instead stops a
        // stranger from making this client's notification area churn on demand.
        if (answer.get().sensitive() && !identity.local()) {
            return Decision.refusal(config.memoryReplyMode(),
                "Only the local player may use " + config.commandPrefix() + canonical + ".");
        }
        StatusCommands.Answer value = answer.get();
        // A local answer is a notification; a chat answer is one line of ordinary chat. Both are
        // non-remembered: a status readout is not part of the conversation.
        Delivery delivery = value.localOnly() ? Delivery.NOTIFY : Delivery.CHAT;
        // Only the overview is mirrored to the admin webhook: a card per figure would flood the
        // channel the moment someone polls two commands in a row.
        String card = "status".equals(value.command()) ? value.command() : null;
        return new Decision(delivery, value.text(), null, false, false, true, false, null, null,
            card);
    }

    /**
     * {@code !hello}: the greeting that suits the current hour.
     *
     * <p>Drawn from the same pools the join greeting uses, so a user configures the wording once.
     */
    private Decision commandHello(String sender, AssistantConfig config, long now,
                                  ServerStats stats) {
        java.time.LocalTime clock = java.time.LocalTime.now();
        String template = GreetingPools.greeting(greetingPools, clock, null);
        return templated(template, sender, config, now, stats);
    }

    /** {@code !gg}: the configured good-game line. */
    private Decision commandGoodGame(String sender, AssistantConfig config, long now,
                                     ServerStats stats) {
        return templated(goodGameReply, sender, config, now, stats);
    }

    /** Expands one configured template and delivers it, or declines when it comes out blank. */
    private Decision templated(String template, String sender, AssistantConfig config, long now,
                               ServerStats stats) {
        if (template == null || template.isBlank()) return Decision.NONE;
        ResponseTemplate.Context context = new ResponseTemplate.Context(
            sender == null ? "" : sender, "", "", memory, now,
            stats == null ? ServerStats.OFFLINE : stats);
        String text = clamp(ResponseTemplate.expand(template, context), config.maxReplyLength());
        if (text.isBlank()) return Decision.NONE;
        return reply(config.memoryReplyMode(), text, false);
    }

    private static String restSuffix(String rest) {
        return rest == null || rest.isEmpty() ? "" : " " + rest;
    }

    /** {@code !help}: every command the assistant answers, as one row per command. */
    private Decision commandHelp(SenderIdentity identity, AssistantConfig config, String rest,
                                 StatusCommands.Settings statusSettings, PermissionSet perms) {
        int page = parsePage(rest);
        ReplyFormat.Embed embed = helpEmbed(identity, config, statusSettings, perms, page);
        if (embed.fields().isEmpty()) {
            return reply(config.memoryReplyMode(), "No commands are available to you.", false);
        }
        return embedReply(config.memoryReplyMode(), embed, false);
    }

    /**
     * How many command rows one help page holds.
     *
     * <p>Four, not eight: a row with its description, aliases and level wraps to two lines in a
     * narrow chat window, and a page has to stay inside the delivery cap or the tail is cut off.
     */
    public static final int HELP_PAGE_SIZE = 4;

    /** The page number in {@code rest} ({@code "2"} or {@code "page 2"}), clamped to 1 or more. */
    static int parsePage(String rest) {
        if (rest == null || rest.isBlank()) return 1;
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("(\\d+)").matcher(rest);
        if (!matcher.find()) return 1;
        try {
            return Math.max(1, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The help block: one row per command the caller may actually use.
     *
     * <p>Derived from {@link CommandCatalog} and the live {@link PermissionSet}, so it cannot list a
     * command that no longer exists, or claim a level that is not the one enforced. Commands the
     * speaker could not run are omitted rather than advertised, and a whitelist is named but never
     * enumerated — printing the members would leak the very list the whitelist protects.
     *
     * @param page the 1-based page to render
     */
    public ReplyFormat.Embed helpEmbed(SenderIdentity identity, AssistantConfig config,
                                       StatusCommands.Settings statusSettings, PermissionSet perms,
                                       int page) {
        AssistantConfig cfg = config == null ? AssistantConfig.DEFAULTS : config;
        PermissionSet live = perms == null ? PermissionSet.permissive() : perms;
        int wanted = Math.max(1, page);

        java.util.List<ReplyFormat.Field> rows = new java.util.ArrayList<>();
        int available = 0;
        int firstShown = 0;
        for (CommandCatalog.Command command : CommandCatalog.all()) {
            // The caller's own view: a command they could not run is not advertised to them, and a
            // rule that was switched off is not listed either.
            PermissionSet.Verdict verdict = live.permits(command.name(), identity);
            if (!verdict.allowed()) continue;
            PermissionLevel level = live.effectiveLevel(command.name());
            available++;
            int index = available - 1;
            if (index < (wanted - 1) * HELP_PAGE_SIZE) continue;
            if (rows.size() >= HELP_PAGE_SIZE) continue;
            if (firstShown == 0) firstShown = available;

            StringBuilder value = new StringBuilder(command.description());
            if (!command.aliases().isEmpty()) {
                value.append(" (").append(String.join(", ", command.aliases())).append(')');
            }
            value.append(" - ").append(levelLabel(level));
            rows.add(new ReplyFormat.Field(cfg.commandPrefix() + command.name(), value.toString()));
        }

        if (statusSettings != null && statusSettings.enabled() && wanted == 1) {
            for (java.util.Map.Entry<String, String> entry : statusSettings.verbs().entrySet()) {
                if (entry.getKey().equals(entry.getValue())) continue;
                rows.add(new ReplyFormat.Field(cfg.commandPrefix() + entry.getValue(),
                    "Renamed figure, same as " + entry.getKey() + "."));
            }
        }

        int pages = Math.max(1, (available + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE);
        String footer;
        if (rows.isEmpty()) {
            footer = "";
        } else if (pages > 1) {
            footer = "Page " + Math.min(wanted, pages) + " of " + pages + " - "
                + cfg.commandPrefix() + "help " + (Math.min(wanted, pages) < pages
                    ? (Math.min(wanted, pages) + 1) : 1) + " for the next.";
        } else {
            footer = available + (available == 1 ? " command." : " commands.");
        }
        return new ReplyFormat.Embed(cfg.name() + " commands", rows, footer,
            ReplyFormat.Style.LIST);
    }

    /** The words a player sees for a level. Whitelist members are never included. */
    public static String levelLabel(PermissionLevel level) {
        if (level == null) return "Everyone";
        return switch (level) {
            case EVERYONE -> "Everyone";
            case WHITELIST -> "Whitelist";
            case ADMINISTRATORS -> "Administrators";
            case LOCAL_ONLY -> "Local only";
            case DISABLED -> "Switched off";
        };
    }

    /** {@code !status}: a short summary of the assistant and what it has done this session. */
    private Decision commandAbout(AssistantConfig config, ChatActivity activity,
                                   PermissionSet perms) {
        ChatActivity seen = activity == null ? new ChatActivity() : activity;
        PermissionSet live = perms == null ? PermissionSet.permissive() : perms;
        java.util.List<ReplyFormat.Field> fields = java.util.List.of(
            new ReplyFormat.Field("Facts", String.valueOf(memory.facts().size())),
            new ReplyFormat.Field("Players known", String.valueOf(memory.knownPlayers().size())),
            new ReplyFormat.Field("Lines seen", String.valueOf(seen.seen())),
            new ReplyFormat.Field("Answered", String.valueOf(seen.answered())),
            new ReplyFormat.Field("Permissions", live.enabled() ? "on" : "off"));
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed(config.name(), fields, "", ReplyFormat.Style.BOX), false);
    }

    /** {@code !activity}: how busy chat has been this session. */
    private Decision commandActivity(AssistantConfig config, ChatActivity activity, long now) {
        if (activity == null || activity.seen() == 0) {
            return reply(config.memoryReplyMode(), "Nothing seen in chat yet.", false);
        }
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        fields.add(new ReplyFormat.Field("Lines", String.valueOf(activity.seen())));
        fields.add(new ReplyFormat.Field("Players", String.valueOf(activity.activePlayers())));
        ChatActivity.PlayerCount busiest = activity.mostActive();
        if (busiest != null) {
            fields.add(new ReplyFormat.Field("Busiest",
                busiest.name() + " (" + busiest.messages() + ")"));
        }
        long span = activity.sessionMs(now);
        if (span > 0) fields.add(new ReplyFormat.Field("Session", ServerStats.formatUptime(span)));
        fields.add(new ReplyFormat.Field("Answered", String.valueOf(activity.answered())));
        if (activity.suppressed() > 0) {
            fields.add(new ReplyFormat.Field("Duplicates dropped",
                String.valueOf(activity.suppressed())));
        }
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Chat activity", fields, "", ReplyFormat.Style.BOX), false);
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

    private Decision commandMemories(String sender, AssistantConfig config) {
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
        return new Decision(deliveryFor(mode), text, null, false, remember, true);
    }

    /**
     * {@code !macros}: the shortcuts the local player can type, as one row per macro.
     *
     * <p>Only enabled, valid macros are listed, and a disabled or broken one is simply absent rather
     * than shown as a shortcut that would not work. The rows carry the usage and the description, so
     * the list doubles as a reference card.
     */
    private Decision commandMacros(AssistantConfig config) {
        java.util.List<ChatMacro> enabled = macros.enabled();
        if (enabled.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "No chat command macros are enabled. Add one in the assistant's Macro screen.", false);
        }
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (ChatMacro macro : enabled) {
            String label = macro.args().isEmpty() ? "no arguments" : macro.usageLine();
            fields.add(new ReplyFormat.Field(macro.usageLine(),
                macro.description().isBlank() ? label : macro.description()));
        }
        String footer = enabled.size() + (enabled.size() == 1 ? " macro" : " macros")
            + " - " + config.commandPrefix() + "macrohelp <name> for one";
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Chat command macros", fields, footer, ReplyFormat.Style.BOX), false);
    }

    /**
     * {@code !who}: the players the client can see, laid out as a block that fits the reply width.
     *
     * <p>Names are grouped into lines rather than crammed into one, so a busy server reads as a list
     * instead of a paragraph. The count is carried as its own field so it stays legible even when the
     * names wrap.
     */
    /**
     * {@code !stats}: every figure the client can see, as a labelled block.
     *
     * <p>A block rather than the old pipe-joined line: one figure per line stays readable when the
     * full set is shown, and a narrow chat window wraps it without turning the separators into
     * noise.
     */
    private Decision commandStats(AssistantConfig config, ServerStats stats) {
        ServerStats live = stats == null ? ServerStats.OFFLINE : stats;
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (ServerStats.Line line : live.lines(ServerStats.Detail.FULL)) {
            fields.add(new ReplyFormat.Field(line.label(), line.value()));
        }
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Server stats", fields, "", ReplyFormat.Style.BOX), false);
    }

    private Decision commandWho(AssistantConfig config, ServerStats stats) {
        ServerStats live = stats == null ? ServerStats.OFFLINE : stats;
        if (!live.inWorld()) {
            return reply(config.memoryReplyMode(), "Not connected.", false);
        }
        java.util.List<String> lines = StatusCommands.whoLines(live, 44);
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        int shown = live.onlinePlayers().size();
        fields.add(new ReplyFormat.Field("Visible", shown + " of " + live.players()));
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) names.append('\n');
            names.append(lines.get(i));
        }
        fields.add(new ReplyFormat.Field("Players", names.toString()));
        if (shown > 0 && shown < live.players()) {
            fields.add(new ReplyFormat.Field("Note", "The server hides some names."));
        }
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Who is online", fields, "", ReplyFormat.Style.BOX), false);
    }

    /** {@code !macrohelp <name>}: one macro's description, syntax and the command it sends. */
    private Decision commandMacroHelp(String rest, AssistantConfig config) {
        String wanted = rest == null ? "" : rest.strip();
        if (wanted.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "Usage: " + config.commandPrefix() + "macrohelp <name>.", false);
        }
        ChatMacro macro = macros.byWord(wanted);
        if (macro == null) {
            return reply(config.memoryReplyMode(),
                "No macro named " + config.commandPrefix() + wanted.strip() + ".", false);
        }
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        fields.add(new ReplyFormat.Field("Usage", macro.usageLine()));
        fields.add(new ReplyFormat.Field("Sends", macro.template()));
        if (!macro.description().isBlank()) {
            fields.add(new ReplyFormat.Field("About", macro.description()));
        }
        if (!macro.aliases().isEmpty()) {
            fields.add(new ReplyFormat.Field("Aliases", String.join(", ", macro.aliases())));
        }
        fields.add(new ReplyFormat.Field("Enabled", macro.enabled() ? "yes" : "no"));
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Macro " + macro.shortcut(), fields, "", ReplyFormat.Style.BOX),
            false);
    }

    /**
     * {@code !mod} / {@code !report}: send a report to the webhook and ping the moderator role.
     *
     * <p>The decision carries the reason rather than sending anything itself: the module owns the
     * webhook settings and the network queue, and keeping the send there means a report goes through
     * exactly the same delivery path as every other card. What the brain contributes is the reason and
     * the permission check, which has already run before this point.
     */
    private Decision commandReport(String sender, String canonical, String rest,
                                   AssistantConfig config) {
        String reason = rest == null ? "" : rest.strip();
        if (reason.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "Usage: " + config.commandPrefix() + canonical + " <what you need help with>.",
                false);
        }
        String who = sender == null || sender.isBlank() ? "someone" : sender;
        String kind = "mod".equalsIgnoreCase(canonical) ? "mod" : "report";
        // Wording is deliberately neutral: the module knows whether the webhook accepted the card,
        // and it replaces this line with a failure notice when nothing was queued. Saying "sent"
        // here would be a promise this layer cannot keep.
        String confirmation = kind.equals("mod")
            ? "Sending a moderator request."
            : "Sending your report to the moderators.";
        String text = clamp(confirmation, config.maxReplyLength());
        return new Decision(deliveryFor(config.memoryReplyMode()), text, null, false, false, true,
            false, null, kind + "|" + who + "|" + reason);
    }

    /** A reply carried as a block, so the caller can render it one line per field. */
    private Decision embedReply(TriggerRule.ReplyMode mode, ReplyFormat.Embed embed,
                                boolean remember) {
        // The text is the complete single-line fallback, never a truncated summary: callers that
        // can only show one line still get every field. The chat path renders the block instead.
        return new Decision(deliveryFor(mode), ReplyFormat.oneLine(embed, Integer.MAX_VALUE), null,
            false, remember, true, false, embed);
    }

    private static Delivery deliveryFor(TriggerRule.ReplyMode mode) {
        return switch (mode == null ? TriggerRule.ReplyMode.NOTIFY : mode) {
            case CHAT -> Delivery.CHAT;
            case NOTIFY -> Delivery.NOTIFY;
            case SILENT -> Delivery.SILENT;
        };
    }

    /**
     * The greeting for {@code now}, with the player and their facts filled in.
     *
     * <p>Returned as a normal {@link Decision} so the module delivers it through the same path as
     * everything else — the same rate limit, the same self-reply guard, the same reply mode.
     *
     * @param identity the player being greeted, or null when the client has no player yet
     * @param pools    the configured greeting pools, keyed by bucket id
     */
    public Decision greeting(SenderIdentity identity, java.util.Map<String, String> pools,
                             AssistantConfig config, java.time.LocalTime now, long epochMillis) {
        AssistantConfig cfg = config == null ? AssistantConfig.DEFAULTS : config;
        String name = identity == null ? "" : identity.name();
        String template = GreetingPools.greeting(pools, now, null);
        ResponseTemplate.Context context = new ResponseTemplate.Context(
            name, "", "", memory, epochMillis, ServerStats.OFFLINE);
        String text = clamp(ResponseTemplate.expand(template, context), cfg.maxReplyLength());
        if (text.isBlank()) return Decision.NONE;
        return reply(cfg.memoryReplyMode(), text, false);
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
