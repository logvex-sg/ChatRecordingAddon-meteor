package com.logvex.addon.assistant.core;

import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommand;
import com.logvex.addon.stats.StatusCommands;
import com.logvex.addon.stats.StatusTemplate;

import java.util.ArrayList;
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
        /** Whisper the text to the speaker, so only they see it. */
        PRIVATE,
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

        /**
         * The lines to deliver, cut to {@code maxLines} without leaving a block open.
         *
         * <p>A caller that sends one message per line has to bound the count, and cutting the
         * rendered list would end a box on a {@code │} row. This renders through
         * {@link ReplyFormat#renderCapped} so the closing rule always survives.
         */
        public java.util.List<String> linesCapped(int width, ReplyFormat.Style style, int maxLines) {
            if (embed == null) return lines(width, style);
            return ReplyFormat.renderCapped(style == null ? embed : withStyle(embed, style),
                width, maxLines);
        }

        private static ReplyFormat.Embed withStyle(ReplyFormat.Embed embed, ReplyFormat.Style style) {
            return new ReplyFormat.Embed(embed.title(), embed.fields(), embed.footer(), style);
        }

        /** A refusal: delivered to the speaker, but nothing was executed. */
        public static Decision refusal(TriggerRule.ReplyMode mode, String reason) {
            Delivery delivery = switch (mode) {
                case CHAT -> Delivery.CHAT;
                case NOTIFY -> Delivery.NOTIFY;
                // A refusal is still an answer to the person who asked, so a private rule keeps its
                // refusal private too rather than leaking "denied" into public chat.
                case PRIVATE -> Delivery.PRIVATE;
                case SILENT -> Delivery.SILENT;
            };
            return new Decision(delivery, reason, null, false, false, true, true);
        }
    }

    private final AssistantMemory memory;

    /** The configured greeting pools, keyed by bucket id; null means the built-in defaults. */
    private java.util.Map<String, String> greetingPools;
    private String goodGameReply = DEFAULT_GOOD_GAME_REPLY;

    /** Refusal for a command that reads the operator's own data, said to a non-local speaker. */
    private static final String NOT_LOCAL =
        "That command reads my owner's own notes, so only they can use it.";

    /**
     * The most rows a list card shows before summarising the rest in the footer.
     *
     * <p>A card is capped at 12 delivered lines, so a list longer than this could never be shown in
     * full anyway. Bounding it here rather than letting the renderer cut means the footer can say
     * how many are hidden, instead of a page ending on an unexplained "+n more".
     */
    private static final int MAX_LIST_ROWS = 8;

    /** The macro table, so {@code !macros} and {@code !macrohelp} can describe it. */
    private MacroSet macros = MacroSet.EMPTY;

    /** The live trigger table, so {@code !triggers} can list what is actually armed. */
    private TriggerSet triggerSet = TriggerSet.empty();

    /**
     * Where filed reports are recorded, or null when the webhook module is unavailable.
     *
     * <p>A supplier rather than the log itself: the log belongs to the relay module, which may be
     * reloaded, and the brain must always read the live one.
     */
    private java.util.function.Supplier<com.logvex.addon.chatlog.core.ReportLog> reportLogSource;

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

    /** Points the brain at the live rule table, so {@code !triggers} lists what will really fire. */
    public void setTriggerSet(TriggerSet triggers) {
        this.triggerSet = triggers == null ? TriggerSet.empty() : triggers;
    }

    public TriggerSet triggerSet() {
        return triggerSet;
    }

    /** Points the brain at the live report log, so {@code !reports} lists what was really filed. */
    public void setReportLogSource(
        java.util.function.Supplier<com.logvex.addon.chatlog.core.ReportLog> source) {
        this.reportLogSource = source;
    }

    /** The live report log, or null when none is wired. */
    private com.logvex.addon.chatlog.core.ReportLog reportLog() {
        return reportLogSource == null ? null : reportLogSource.get();
    }

    /**
     * How a trigger rule's enabled flag is persisted.
     *
     * <p>Null means the caller can read rules but not change them, which is what a headless test
     * wants: {@code !trigger off goodbye} then answers "no way to save that" instead of pretending
     * the change stuck. The module wires this to its persisted rule list, because the brain owns no
     * settings and must not start.
     */
    public interface TriggerToggle {
        /**
         * Turns the rule matching {@code pattern} on or off.
         *
         * @return null when it worked, or a sentence explaining why it did not
         */
        String setEnabled(String pattern, boolean enabled);
    }

    private TriggerToggle triggerToggle;

    /**
     * Every rule, including the disabled ones, for {@code !trigger} to resolve a name against.
     *
     * <p>{@link #triggerSet} cannot be used for this: a {@link TriggerSet} holds only the rules that
     * are on, so a rule that was just switched off would vanish from the lookup and could never be
     * switched back on.
     */
    private java.util.function.Supplier<List<TriggerRule>> triggerCatalog;

    /** Wires the callback that persists a trigger's enabled flag; null disables the command. */
    public void setTriggerToggle(TriggerToggle toggle) {
        this.triggerToggle = toggle;
    }

    /** Wires the full rule list, disabled rules included; null falls back to the active set. */
    public void setTriggerCatalog(java.util.function.Supplier<List<TriggerRule>> catalog) {
        this.triggerCatalog = catalog;
    }

    /** Every rule {@code !trigger} may act on, enabled or not. */
    private List<TriggerRule> knownRules() {
        if (triggerCatalog != null) {
            List<TriggerRule> all = triggerCatalog.get();
            if (all != null) return all;
        }
        return triggerSet == null ? List.of() : triggerSet.rules();
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

    /**
     * Evaluates one incoming message, trying every source in priority order.
     *
     * <p>Kept for callers that want the whole decision in one call (and for tests). The live runtime
     * uses the granular {@link #trigger} and {@link #command} methods instead, because each source
     * has its own cooldown and rate-limit treatment.
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

    // ------------------------------------------------------------------ triggers

    private Decision fromTrigger(TriggerRule rule, String sender, String content,
                                 AssistantConfig config, long now, ServerStats stats) {
        ResponseTemplate.Context context = new ResponseTemplate.Context(
            sender, content, stats.server(), memory, now, stats,
            config.name(), config.commandPrefix());
        String text = ResponseTemplate.expand(rule.chosenReply(null), context);
        text = clamp(text, config.maxReplyLength());

        Delivery delivery = switch (rule.mode()) {
            case CHAT -> Delivery.CHAT;
            case NOTIFY -> Delivery.NOTIFY;
            case PRIVATE -> Delivery.PRIVATE;
            case SILENT -> Delivery.SILENT;
        };
        if (text.isBlank() && delivery == Delivery.CHAT) delivery = Delivery.SILENT;

        boolean remember = rule.remember() || config.rememberConversations();
        if (remember) {
            memory.recordConversation(sender, content, text, now);
            memory.incrementCounter("replies", 1);
        }
        // An embed rule delivers the same figures as a labelled block, so the values line up instead
        // of running together in one sentence. The one-line text stays as the fallback for a caller
        // that can only show a single line, and as what memory records. A command-shaped trigger
        // carries its catalogue description in the footer, so the card says what it reports.
        ReplyFormat.Embed embed = rule.wantsEmbed(text) ? rule.toEmbed(text, rule.pattern()) : null;
        if (embed != null && rule.isCommandShaped()) {
            String description = CommandCatalog.find(rule.pattern().replaceFirst("^[!/]", ""))
                .map(CommandCatalog.Command::description).orElse("");
            if (!description.isEmpty()) {
                embed = new ReplyFormat.Embed(embed.title(), embed.fields(), description,
                    embed.style());
            }
        }
        return new Decision(delivery, text, rule, rule.sound(), remember, false, false, embed);
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
            case "macros" -> commandMacros(config, rest);
            case "triggers" -> commandTriggers(identity, config, rest);
            // Changing the trigger table is an operator action. Without this gate a stranger could
            // type !trigger off in public chat and silence the operator's answers for everyone.
            case "trigger" -> identity.local()
                ? commandTrigger(sender, config, rest)
                : reply(config.memoryReplyMode(), NOT_LOCAL, false);
            case "macrohelp" -> commandMacroHelp(rest, config);
            case "share" -> commandShare(config);
            case "mod", "report" -> commandReport(sender, canonical, rest, config);
            // modcall is its own module for the case where the assistant is off, but it is also in
            // the command table, so the assistant must answer it too. Without this case the line
            // fell through to public chat and the player's reason was broadcast to everyone.
            case "modcall" -> commandReport(sender, "mod", rest, config);
            // Both read the operator's own private data - who they reported and what they
            // remember about a player - so they are gated on the local speaker here rather than
            // left to the permissions setting, which is off by default. Without this a stranger
            // typing !reports in public chat would be shown the operator's report history.
            case "reports" -> identity.local()
                ? commandReports(rest, config)
                : reply(config.memoryReplyMode(), NOT_LOCAL, false);
            case "player" -> identity.local()
                ? commandPlayer(rest, config, activity)
                : reply(config.memoryReplyMode(), NOT_LOCAL, false);
            case "summary" -> commandSummary(sender, rest, config);
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
        StatusTemplate.Context context = new StatusTemplate.Context(identity.name(), ping,
            config.name(), config.commandPrefix());
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
        // Every figure answers as a card, built from the operator's own template. The template is
        // still what decides the wording and order, so a customised answer keeps its customisation;
        // only the layout is imposed. The one-line text stays as the fallback and as what a caller
        // that can show only one line sends.
        ReplyFormat.Embed embed = statusEmbed(config, value);
        // Only the overview is mirrored to the admin webhook: a card per figure would flood the
        // channel the moment someone polls two commands in a row.
        String card = "status".equals(value.command()) ? value.command() : null;
        return new Decision(delivery, value.text(), null, false, false, true, false, embed, null,
            card);
    }

    /**
     * The card for one status command.
     *
     * <p>The footer carries the command's one-line description, so the block explains itself
     * instead of repeating its own name back at the reader. The description is optional: an
     * unknown verb still renders, just without a footer.
     */
    private static ReplyFormat.Embed statusEmbed(AssistantConfig config, StatusCommands.Answer value) {
        ReplyFormat.Embed card = ReplyFormat.fromTemplate(
            config.commandPrefix() + value.command(), value.text());
        if (card == null) return null;
        String description = CommandCatalog.find(value.command())
            .map(CommandCatalog.Command::description).orElse("");
        return new ReplyFormat.Embed(card.title(), card.fields(), description, card.style());
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
            stats == null ? ServerStats.OFFLINE : stats,
            config.name(), config.commandPrefix());
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
        // A name is a request for that one command, not a page. Without this, "!help modcall" was
        // read as "page 1" and silently answered with the whole list.
        CommandCatalog.Command single = namedCommand(rest);
        if (single != null) {
            return commandHelpDetail(identity, config, single, perms);
        }
        int page = parsePage(rest);
        ReplyFormat.Embed embed = helpEmbed(identity, config, statusSettings, perms, page);
        if (embed.fields().isEmpty()) {
            return reply(config.memoryReplyMode(), "No commands are available to you.", false);
        }
        return embedReply(config.memoryReplyMode(), embed, false);
    }

    /**
     * The command {@code rest} names, or null when it names none.
     *
     * <p>Only a single token is treated as a name. {@code "page 2"} and a bare number stay on the
     * paging path, so the two ways of using the command do not collide.
     */
    private static CommandCatalog.Command namedCommand(String rest) {
        if (rest == null) return null;
        String text = rest.strip();
        if (text.isEmpty()) return null;
        String word = text.toLowerCase(Locale.ROOT);
        if (word.startsWith("page")) return null;
        if (word.chars().allMatch(Character::isDigit)) return null;
        if (text.indexOf(' ') >= 0) return null;
        // find() matches canonical names only, so an alias such as "shortcuts" has to be resolved
        // through the alias table before the lookup or it reads as an unknown command.
        String canonical = CommandCatalog.canonical(word).orElse(word);
        return CommandCatalog.find(canonical).orElse(null);
    }

    /**
     * One command, in full: what it does, how to spell it, and whether the caller may run it.
     *
     * <p>Refused rather than omitted when the caller could not run it, unlike the list: asking about
     * a named command is not the same as being offered it, and saying so is more useful than an
     * empty answer.
     */
    private Decision commandHelpDetail(SenderIdentity identity, AssistantConfig config,
                                       CommandCatalog.Command command, PermissionSet perms) {
        PermissionSet live = perms == null ? PermissionSet.permissive() : perms;
        PermissionSet.Verdict verdict = live.permits(command.name(), identity);
        PermissionLevel level = live.effectiveLevel(command.name());
        String prefix = config.commandPrefix();

        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        fields.add(new ReplyFormat.Field("Usage", prefix + command.name()));
        fields.add(new ReplyFormat.Field("About", command.description()));
        if (!command.aliases().isEmpty()) {
            java.util.List<String> spelled = new java.util.ArrayList<>();
            for (String alias : command.aliases()) spelled.add(prefix + alias);
            fields.add(new ReplyFormat.Field("Also", String.join(", ", spelled)));
        }
        fields.add(new ReplyFormat.Field("Access", levelLabel(level)));
        if (!verdict.allowed()) {
            fields.add(new ReplyFormat.Field("You", "may not run this"));
        }
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed(prefix + command.name(), fields, "", ReplyFormat.Style.BOX), false);
    }

    /**
     * How many command rows one help page holds.
     *
     * <p>Five, not eight or six: a row carrying aliases wraps to two lines at the default width, and
     * the delivery cap is twelve lines including both frame rules. Six rows meant a page could need
     * fourteen lines, so the tail was cut and reported as "+1 more" — a command the reader was told
     * about but never shown. Five rows is the largest count that always fits.
     */
    public static final int HELP_PAGE_SIZE = 5;

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
            // The permission is only worth a column when it is not the default. Repeating
            // "- Everyone" on every row pushed the description off the line for no information.
            if (level != null && level != PermissionLevel.EVERYONE) {
                value.append(" [").append(levelLabel(level)).append(']');
            }
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
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        fields.add(ReplyFormat.Field.section("Memory"));
        fields.add(new ReplyFormat.Field("Facts", String.valueOf(memory.facts().size())));
        fields.add(new ReplyFormat.Field("Players known", String.valueOf(memory.knownPlayers().size())));
        fields.add(ReplyFormat.Field.section("This session"));
        fields.add(new ReplyFormat.Field("Lines seen", String.valueOf(seen.seen())));
        fields.add(new ReplyFormat.Field("Answered", String.valueOf(seen.answered())));
        // A share of the lines it chose to answer reads faster as a bar than as two counts.
        double share = seen.seen() <= 0 ? 0 : (double) seen.answered() / seen.seen();
        fields.add(ReplyFormat.Field.meter("Reply rate",
            Math.round(share * 100) + "%", share));
        fields.add(ReplyFormat.Field.section("Controls"));
        fields.add(new ReplyFormat.Field("Permissions", live.enabled() ? "on" : "off"));
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
        return embedReply(config.memoryReplyMode(), key + " = " + value,
            new ReplyFormat.Embed(config.commandPrefix() + "fact",
                List.of(new ReplyFormat.Field(key, value)), "", ReplyFormat.Style.BOX), false);
    }

    private Decision commandFacts(AssistantConfig config) {
        List<AssistantMemory.Fact> facts = memory.facts();
        if (facts.isEmpty()) {
            return reply(config.memoryReplyMode(), "I have no stored facts.", false);
        }
        List<ReplyFormat.Field> rows = new ArrayList<>(facts.size());
        int shown = 0;
        for (AssistantMemory.Fact fact : facts) {
            if (shown == MAX_LIST_ROWS) {
                break;
            }
            rows.add(new ReplyFormat.Field(fact.key(), clamp(fact.value(), 24)));
            shown++;
        }
        String footer = facts.size() > shown
            ? "+" + (facts.size() - shown) + " more"
            : "";
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Stored facts", rows, footer, ReplyFormat.Style.BOX), false);
    }

    private Decision commandMemories(String sender, AssistantConfig config) {
        List<ReplyFormat.Field> rows = new ArrayList<>(4);
        rows.add(new ReplyFormat.Field("Facts", String.valueOf(memory.facts().size())));
        rows.add(new ReplyFormat.Field("Players", String.valueOf(memory.knownPlayers().size())));
        rows.add(new ReplyFormat.Field("Remembered lines",
            String.valueOf(memory.historySize())));
        if (sender != null && !sender.isBlank()) {
            rows.add(new ReplyFormat.Field("Your facts",
                String.valueOf(memory.playerFacts(sender).size())));
        }
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("What I remember", rows, "", ReplyFormat.Style.BOX), false);
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
        List<AssistantMemory.ConversationEntry> recent = memory.history(MAX_LIST_ROWS);
        if (recent.isEmpty()) {
            return reply(config.memoryReplyMode(), "Nothing remembered yet.", false);
        }
        List<ReplyFormat.Field> rows = new ArrayList<>(recent.size());
        for (AssistantMemory.ConversationEntry entry : recent) {
            rows.add(new ReplyFormat.Field(clamp(entry.question(), 28),
                clamp(entry.answer(), 28)));
        }
        String footer = memory.historySize() > recent.size()
            ? "+" + (memory.historySize() - recent.size()) + " older"
            : "";
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Recent exchanges", rows, footer, ReplyFormat.Style.BOX), false);
    }

    private Decision commandWhoAmI(String sender, AssistantConfig config) {
        String name = sender == null || sender.isBlank() ? "someone" : sender;
        List<AssistantMemory.Fact> perPlayer = memory.playerFacts(name);
        // history(0) is empty by contract, so ask for everything and count this player's share.
        long asked = memory.allHistory().stream()
            .filter(entry -> entry.player() != null && entry.player().equalsIgnoreCase(name))
            .count();
        List<ReplyFormat.Field> rows = new ArrayList<>(3);
        rows.add(new ReplyFormat.Field("Player", name));
        rows.add(new ReplyFormat.Field("Your facts", String.valueOf(perPlayer.size())));
        rows.add(new ReplyFormat.Field("Asked me", asked + (asked == 1 ? " time" : " times")));
        String text = "You are " + name + (perPlayer.isEmpty() ? "."
            : ", and I know " + perPlayer.size() + " thing"
                + (perPlayer.size() == 1 ? "" : "s") + " about you.");
        return embedReply(config.memoryReplyMode(), text,
            new ReplyFormat.Embed("About you", rows,
                perPlayer.isEmpty()
                    ? config.commandPrefix() + "remember " + name + " <key> = <value> to add one"
                    : "",
                ReplyFormat.Style.BOX), false);
    }

    private Decision reply(TriggerRule.ReplyMode mode, String text, boolean remember) {
        return new Decision(deliveryFor(mode), text, null, false, remember, true);
    }

    /**
     * {@code !triggers}: the rules that are armed right now, as one page of rows.
     *
     * <p>This is the answer to "what can I type?", which {@code !help} cannot give: help lists the
     * commands the dispatcher knows, while this lists the keyword rules that will actually fire. A
     * rule the speaker could never use is hidden — a rule pinned to another player, or one whose
     * reply is a state-changing command only the local player may run — so the list never advertises
     * something that would be silently ignored.
     *
     * @param rest the page number, or a word to filter by
     */
    private Decision commandTriggers(SenderIdentity identity, AssistantConfig config, String rest) {
        // The full catalog, so a rule that was switched off still appears to the local player,
        // marked "[off]". A remote player is only shown what is actually running.
        java.util.List<TriggerRule> armed = new java.util.ArrayList<>();
        for (TriggerRule rule : knownRules()) {
            if (!visibleTo(rule, identity)) continue;
            armed.add(rule);
        }
        if (armed.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "No triggers are active. Add one in the assistant's Trigger screen.", false);
        }

        String filter = filterWord(rest);
        if (!filter.isEmpty()) {
            armed.removeIf(rule -> !rule.pattern().toLowerCase(Locale.ROOT)
                .contains(filter.toLowerCase(Locale.ROOT)));
            if (armed.isEmpty()) {
                return reply(config.memoryReplyMode(),
                    "No trigger matches '" + filter + "'.", false);
            }
        }

        int page = parsePage(rest);
        int pages = Math.max(1, (armed.size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE);
        int wanted = Math.min(page, pages);
        int from = (wanted - 1) * HELP_PAGE_SIZE;
        int to = Math.min(armed.size(), from + HELP_PAGE_SIZE);

        java.util.List<ReplyFormat.Field> rows = new java.util.ArrayList<>();
        for (int i = from; i < to; i++) {
            TriggerRule rule = armed.get(i);
            rows.add(new ReplyFormat.Field(triggerLabel(rule), triggerValue(rule)));
        }
        String footer = armed.size() + (armed.size() == 1 ? " trigger" : " triggers")
            + " - page " + wanted + "/" + pages;
        if (pages > 1) footer += " - " + config.commandPrefix() + "triggers " + (wanted + 1);
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Active triggers", rows, footer, ReplyFormat.Style.BOX), false);
    }

    /**
     * {@code !trigger on|off <pattern>}: flips one rule's enabled flag and saves it.
     *
     * <p>The trigger table is what makes the assistant answer, and the shipped one is full of
     * courtesies a player may not want ("cya" answers goodbye, "gg" answers good game). Without this
     * command the only way to silence one was to delete it in the settings screen, which also threw
     * away its reply text. Disabling keeps the rule and its wording, so it can be turned back on.
     *
     * <p>The pattern is matched the way the list shows it: a bare name that is a prefix of exactly
     * one rule is enough ({@code !trigger off goodbye} for the {@code goodbye} rule), and an
     * ambiguous prefix lists the candidates rather than guessing.
     */
    private Decision commandTrigger(String sender, AssistantConfig config, String rest) {
        String body = rest == null ? "" : rest.strip();
        String verb = body.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String target = body.length() > verb.length() ? body.substring(verb.length()).strip() : "";

        List<TriggerRule> rules = knownRules();
        if (body.isEmpty() || target.isEmpty()) {
            return reply(config.memoryReplyMode(), "Usage: " + config.commandPrefix()
                + "trigger on|off <name>, for example " + config.commandPrefix()
                + "trigger off goodbye", false);
        }
        boolean enable;
        switch (verb) {
            case "on", "enable", "true" -> enable = true;
            case "off", "disable", "false" -> enable = false;
            case "toggle" -> {
                TriggerRule found = findRule(rules, target);
                if (found == null || ambiguous(rules, target)) {
                    return triggerLookupFailure(rules, config, target);
                }
                enable = !found.enabled();
            }
            default -> {
                return reply(config.memoryReplyMode(), "Say on or off: " + config.commandPrefix()
                    + "trigger off goodbye", false);
            }
        }

        TriggerRule found = findRule(rules, target);
        if (found == null || ambiguous(rules, target)) return triggerLookupFailure(rules, config, target);

        if (triggerToggle == null) {
            return reply(config.memoryReplyMode(),
                "Triggers can only be changed from the assistant's settings screen.", false);
        }
        String problem = triggerToggle.setEnabled(found.pattern(), enable);
        if (problem != null) return reply(config.memoryReplyMode(), problem, false);

        return reply(config.memoryReplyMode(), "Trigger '" + found.pattern() + "' is now "
            + (enable ? "on" : "off") + ".", true);
    }

    /**
     * The rule {@code target} names: its pattern, one of its aliases, or a unique prefix of either.
     *
     * <p>Aliases are included because that is how a rule is usually known. A player sees "cya" in
     * chat, not the {@code goodbye} rule that answers it, so a name-only lookup would answer "no
     * trigger is called 'cya'" for the very word they want to silence. A pattern always wins over an
     * alias, so an exact name is never ambiguous.
     */
    private static TriggerRule findRule(List<TriggerRule> rules, String target) {
        String needle = target.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return null;

        for (TriggerRule rule : rules) {
            if (rule.pattern().strip().toLowerCase(Locale.ROOT).equals(needle)) return rule;
        }
        for (TriggerRule rule : rules) {
            if (hasAlias(rule, needle)) return rule;
        }
        TriggerRule prefix = null;
        int hits = 0;
        for (TriggerRule rule : rules) {
            if (rule.pattern().toLowerCase(Locale.ROOT).startsWith(needle) || aliasStartsWith(rule, needle)) {
                if (++hits > 1) return null;
                prefix = rule;
            }
        }
        return prefix;
    }

    /** True when {@code rule} lists {@code needle} as one of its aliases. */
    private static boolean hasAlias(TriggerRule rule, String needle) {
        String aliases = rule.aliases() == null ? "" : rule.aliases();
        for (String alias : aliases.split(",")) {
            if (alias.strip().toLowerCase(Locale.ROOT).equals(needle)) return true;
        }
        return false;
    }

    /** True when one of {@code rule}'s aliases starts with {@code needle}. */
    private static boolean aliasStartsWith(TriggerRule rule, String needle) {
        String aliases = rule.aliases() == null ? "" : rule.aliases();
        for (String alias : aliases.split(",")) {
            if (alias.strip().toLowerCase(Locale.ROOT).startsWith(needle)) return true;
        }
        return false;
    }

    /** True when {@code target} matches more than one rule, so acting on it would be a guess. */
    private static boolean ambiguous(List<TriggerRule> rules, String target) {
        String needle = target.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return false;
        int exact = 0;
        int hits = 0;
        for (TriggerRule rule : rules) {
            String pattern = rule.pattern().strip().toLowerCase(Locale.ROOT);
            if (pattern.equals(needle) || hasAlias(rule, needle)) exact++;
            else if (pattern.startsWith(needle) || aliasStartsWith(rule, needle)) hits++;
        }
        if (exact >= 1) return exact > 1;
        return hits > 1;
    }

    /** Says why a name could not be resolved, naming the candidates when it was only ambiguous. */
    private Decision triggerLookupFailure(List<TriggerRule> rules, AssistantConfig config,
                                          String target) {
        List<String> candidates = new java.util.ArrayList<>();
        String needle = target.strip().toLowerCase(Locale.ROOT);
        for (TriggerRule rule : rules) {
            if (rule.pattern().toLowerCase(Locale.ROOT).startsWith(needle)) {
                candidates.add(rule.pattern());
            }
        }
        if (candidates.isEmpty()) {
            return reply(config.memoryReplyMode(), "No trigger is called '" + target
                + "'. " + config.commandPrefix() + "triggers lists them.", false);
        }
        return reply(config.memoryReplyMode(), "Which one? " + String.join(", ", candidates), false);
    }

    /**
     * True when {@code rule} is worth showing to this speaker.
     *
     * <p>A rule pinned to someone else is not their business, and a rule whose reply is a command
     * only the local player may run would be a false promise in the list, so both are hidden.
     */
    private static boolean visibleTo(TriggerRule rule, SenderIdentity identity) {
        if (rule == null || rule.pattern().isBlank()) return false;
        boolean local = identity != null && identity.local();
        // A rule that is off is shown to the local player only, marked "[off]": it is how they find
        // the name to switch back on. A remote player is told only what is actually running.
        if (!rule.enabled() && !local) return false;
        if (!rule.allowsCommandFrom(local)) return false;
        return rule.sender().isBlank() || rule.senderAllowed(identity == null ? "" : identity.name());
    }

    /** The left column: the word to type, annotated when it is not the default whisper. */
    private static String triggerLabel(TriggerRule rule) {
        String label = rule.pattern();
        // A rule that is off is still listed, marked as such: it is the only place the operator can
        // see what "!trigger on <name>" would bring back.
        if (!rule.enabled()) return label + " [off]";
        return switch (rule.mode()) {
            case PRIVATE -> label;
            case CHAT -> label + " [chat]";
            case NOTIFY -> label + " [local]";
            case SILENT -> label + " [silent]";
        };
    }

    /** The right column: what the trigger does, without repeating its own reply text. */
    private static String triggerValue(TriggerRule rule) {
        if (rule.isCommandReply()) return "runs /" + rule.commandVerb();
        String text = rule.reply() == null ? "" : rule.reply().strip();
        if (text.isEmpty()) return "no reply";
        return clampStatic(text, 60);
    }

    /** A page argument's word, when it is not a page number. */
    private static String filterWord(String rest) {
        if (rest == null || rest.isBlank()) return "";
        String trimmed = rest.strip();
        if (trimmed.matches("(?i)page\\s*\\d+") || trimmed.matches("\\d+")) return "";
        return trimmed;
    }

    private static String clampStatic(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    /**
     * How many macro rows one {@code !macros} page holds.
     *
     * <p>Six, matching a help page: the reply cap is twelve lines and a row with a wrapped
     * description can take two, so a larger page would be cut and the tail lost.
     */
    public static final int MACROS_PAGE_SIZE = 6;

    /**
     * {@code !macros}: the shortcuts the local player can type, as one row per macro.
     *
     * <p>Only enabled, valid macros are listed, and a disabled or broken one is simply absent rather
     * than shown as a shortcut that would not work. The rows carry the usage and the description, so
     * the list doubles as a reference card.
     *
     * <p>Paged, because the shipped set is fifteen macros and a single block is cut at twelve lines:
     * the tail used to be reported as "+9 more" with no way to see it. The page number is the same
     * argument {@code !help} takes.
     */
    private Decision commandMacros(AssistantConfig config, String rest) {
        java.util.List<ChatMacro> enabled = macros.enabled();
        if (enabled.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "No chat command macros are enabled. Add one in the assistant's Macro screen.", false);
        }
        int wanted = parsePage(rest);
        int pages = Math.max(1, (enabled.size() + MACROS_PAGE_SIZE - 1) / MACROS_PAGE_SIZE);
        int page = Math.min(wanted, pages);
        int from = (page - 1) * MACROS_PAGE_SIZE;
        int to = Math.min(from + MACROS_PAGE_SIZE, enabled.size());

        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (ChatMacro macro : enabled.subList(from, to)) {
            String label = macro.args().isEmpty() ? "no arguments" : macro.usageLine();
            String about = macro.description().isBlank() ? label : macro.description();
            // A shared macro runs as the operator, so it is called out here: the list is what a
            // user scans, and "why did someone else run /spawn" is answered by seeing the marker.
            if (macro.remote()) about = about + " (shared)";
            fields.add(new ReplyFormat.Field(macro.usageLine(), about));
        }
        String footer;
        if (pages > 1) {
            footer = "Page " + page + " of " + pages + " - " + config.commandPrefix() + "macros "
                + (page < pages ? (page + 1) : 1) + " for the next.";
        } else {
            footer = enabled.size() + (enabled.size() == 1 ? " macro" : " macros")
                + " - " + config.commandPrefix() + "macrohelp <name> for one";
        }
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Chat command macros", fields, footer, ReplyFormat.Style.BOX), false);
    }

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

    /**
     * {@code !who}: the players the client can see, laid out as a block that fits the reply width.
     *
     * <p>Names are grouped into lines rather than crammed into one, so a busy server reads as a list
     * instead of a paragraph. The count is carried as its own field so it stays legible even when the
     * names wrap.
     */
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
        fields.add(new ReplyFormat.Field("Shared",
            macro.remote() ? "yes, other players may run it" : "no"));
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Macro " + macro.shortcut(), fields, "", ReplyFormat.Style.BOX),
            false);
    }

    /**
     * {@code !share}: which macros other players may run.
     *
     * <p>Read-only. Sharing is a security decision — a shared macro runs a command as the operator —
     * so it is made in the module, where the allow-list and the on/off switch live, and this command
     * only reports the result. A command that could silently flip sharing on would make that
     * decision reachable from public chat.
     */
    private Decision commandShare(AssistantConfig config) {
        java.util.List<ChatMacro> shared = macros.remote();
        String prefix = config.commandPrefix();
        if (shared.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "No macros are shared. Mark one 'remote' in the assistant's Macro screen to let "
                    + "other players run it.", false);
        }
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        for (ChatMacro macro : shared) {
            fields.add(new ReplyFormat.Field(macro.usageLine(),
                macro.description().isBlank() ? macro.template() : macro.description()));
        }
        String footer = shared.size() + (shared.size() == 1 ? " shared macro" : " shared macros")
            + " - " + prefix + "macrohelp <name> for one";
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Shared macros", fields, footer, ReplyFormat.Style.BOX), false);
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

    /**
     * {@code !player <name>}: everything this client knows about one player, in one card.
     *
     * <p>The pieces already existed - remembered facts, conversation history, session activity,
     * notes, renames - but each was only reachable through its own command, so answering "who is
     * this person" meant four commands. This composes them and states which are absent rather than
     * showing empty rows.
     *
     * <p>Only figures the client actually holds are shown. A player who has never spoken has no
     * activity row at all, which is different from having zero messages.
     */
    private Decision commandPlayer(String rest, AssistantConfig config, ChatActivity activity) {
        String name = rest == null ? "" : rest.strip();
        if (name.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "Usage: " + config.commandPrefix() + "player <name>", false);
        }
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();

        java.util.List<AssistantMemory.Fact> facts = memory.playerFacts(name);
        for (AssistantMemory.Fact fact : facts) {
            fields.add(new ReplyFormat.Field(fact.key(), clamp(fact.value(), 60)));
        }

        if (activity != null) {
            long messages = activity.messagesFrom(name);
            if (messages > 0) {
                fields.add(new ReplyFormat.Field("Messages", Long.toString(messages)));
            }
        }

        // history(0) is empty by contract (it is "at most zero"), so ask for everything.
        long lines = memory.allHistory().stream()
            .filter(entry -> entry.player() != null && entry.player().equalsIgnoreCase(name))
            .count();
        if (lines > 0) {
            fields.add(new ReplyFormat.Field("Asked me", lines + (lines == 1 ? " time" : " times")));
        }

        if (fields.isEmpty()) {
            return reply(config.memoryReplyMode(),
                "I know nothing about " + name + " yet. Nothing was remembered and they have not "
                    + "spoken since I started watching.", false);
        }
        String footer = facts.size() + (facts.size() == 1 ? " fact" : " facts")
            + " - " + config.commandPrefix() + "remember " + name + " <key> <value> to add more";
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed(name, fields, footer, ReplyFormat.Style.BOX), false);
    }

    /**
     * {@code !reports [filter]}: what this client has filed, newest first.
     *
     * <p>Reads the live log rather than a copy, and says plainly when the log is unavailable rather
     * than answering "no reports" - those are different facts and only one of them is true.
     */
    private Decision commandReports(String rest, AssistantConfig config) {
        com.logvex.addon.chatlog.core.ReportLog log = reportLog();
        if (log == null) {
            return reply(config.memoryReplyMode(),
                "The report log is unavailable: the webhook relay module is not loaded.", false);
        }
        java.util.List<com.logvex.addon.chatlog.core.ReportLog.Record> found =
            log.search(rest == null ? "" : rest);
        if (found.isEmpty()) {
            String what = rest == null || rest.isBlank() ? "" : " matching \"" + rest.strip() + "\"";
            return reply(config.memoryReplyMode(), "No reports" + what + " in the log.", false);
        }
        java.util.List<ReplyFormat.Field> fields = new java.util.ArrayList<>();
        int shown = 0;
        for (com.logvex.addon.chatlog.core.ReportLog.Record record : found) {
            if (shown == 8) break;
            String label = record.delivered() ? record.kind() : record.kind() + " (failed)";
            fields.add(new ReplyFormat.Field(
                com.logvex.addon.chatlog.core.ReportFormatting.shortTime(record.atMs()) + " " + label,
                record.reporter() + ": " + clamp(record.reason(), 60)));
            shown++;
        }
        String footer = found.size() + (found.size() == 1 ? " report" : " reports")
            + (log.failures() > 0 ? ", " + log.failures() + " failed" : "")
            + (found.size() > shown ? ", showing " + shown : "");
        return embedReply(config.memoryReplyMode(),
            new ReplyFormat.Embed("Filed reports", fields, footer, ReplyFormat.Style.BOX), false);
    }

    /**
     * {@code !summary [note]}: send a chat summary report to the configured webhook.
     *
     * <p>Encoded through the same {@code kind|who|note} channel as a report, with a distinct kind,
     * because the delivery, permission gate and failure notice are all already handled there. The
     * module decides whether a webhook is configured; this layer only checks that the request makes
     * sense on its own.
     */
    private Decision commandSummary(String sender, String rest, AssistantConfig config) {
        String note = rest == null ? "" : rest.strip();
        String who = sender == null || sender.isBlank() ? "someone" : sender;
        String text = clamp("Sending a chat summary report.", config.maxReplyLength());
        return new Decision(deliveryFor(config.memoryReplyMode()), text, null, false, false, true,
            false, null, "summary|" + who + "|" + note);
    }

    /** A reply carried as a block, so the caller can render it one line per field. */
    private Decision embedReply(TriggerRule.ReplyMode mode, ReplyFormat.Embed embed,
                                boolean remember) {
        // The text is the complete single-line fallback, never a truncated summary: callers that
        // can only show one line still get every field. The chat path renders the block instead.
        return new Decision(deliveryFor(mode), ReplyFormat.oneLine(embed, Integer.MAX_VALUE), null,
            false, remember, true, false, embed);
    }

    /**
     * A card with a hand-written one-line fallback.
     *
     * <p>{@link #embedReply} derives the fallback by flattening the block, which reads as
     * {@code "Title | label value"} — fine for a table, poor for an answer that has a natural
     * sentence of its own. Those callers pass it here so a single-line consumer still gets prose.
     */
    private Decision embedReply(TriggerRule.ReplyMode mode, String text, ReplyFormat.Embed embed,
                                boolean remember) {
        return new Decision(deliveryFor(mode), text, null, false, remember, true, false, embed);
    }

    private static Delivery deliveryFor(TriggerRule.ReplyMode mode) {
        return switch (mode == null ? TriggerRule.ReplyMode.NOTIFY : mode) {
            case CHAT -> Delivery.CHAT;
            case NOTIFY -> Delivery.NOTIFY;
            case PRIVATE -> Delivery.PRIVATE;
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
