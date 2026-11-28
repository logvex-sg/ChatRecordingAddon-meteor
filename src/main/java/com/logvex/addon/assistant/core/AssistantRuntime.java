package com.logvex.addon.assistant.core;

import com.logvex.addon.stats.ServerStats;
import com.logvex.addon.stats.StatusCommands;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The live assistant state: memory, rules, configuration and the rate limits, with no Minecraft
 * dependency.
 *
 * <p>The module owns one of these and drives it from the event bus. Keeping the state here rather
 * than in the module means the whole decision path — the duplicate filter, the per-rule cooldown,
 * the permission gate and the global rate limit — can be exercised in a unit test with no game
 * running.
 *
 * <p>Order of checks for one incoming line, and every one of them is a refusal rather than a repair:
 * <ol>
 *   <li>a blank line is ignored;</li>
 *   <li>a duplicate of a line already answered inside the window is dropped;</li>
 *   <li>a configured trigger is tried, under its own cooldown;</li>
 *   <li>a command is parsed and authorised by {@link PermissionSet} <em>before</em> it runs;</li>
 *   <li>the global per-minute budget gates whichever of the above produced a reply.</li>
 * </ol>
 */
public final class AssistantRuntime {
    /** How many replies the global rate limiter looks at. */
    private static final int RATE_WINDOW = 60_000;

    /** How long a repeated message is treated as a duplicate. */
    public static final long DUPLICATE_WINDOW_MS = 4_000L;

    /** How many refusals a minute the assistant will say out loud. */
    private static final int DENIALS_PER_MINUTE = 6;

    /** How many reports one player may send a minute, so the webhook cannot be flooded. */
    private static final int REPORTS_PER_MINUTE = 2;

    private final AssistantMemory memory;
    private final AssistantBrain brain;
    private final TriggerCooldownTracker cooldowns = new TriggerCooldownTracker();
    private final DuplicateSuppressor duplicates = new DuplicateSuppressor(DUPLICATE_WINDOW_MS);
    private final ChatActivity activity = new ChatActivity();

    private TriggerSet triggers = TriggerSet.empty();
    private final ReactionLog reactionLog = new ReactionLog();
    private AssistantConfig config = AssistantConfig.DEFAULTS;
    private PermissionSet permissions = PermissionSet.permissive();
    private boolean duplicateFilterEnabled = true;
    private boolean helpEnabled = true;

    /** Timestamps of recent replies, used for the global per-minute rate limit. */
    private final Deque<Long> recentReplies = new ArrayDeque<>();

    /** Timestamps of recent refusals, which are capped separately from real replies. */
    private final Deque<Long> recentDenials = new ArrayDeque<>();

    /** Per-player timestamps of recent reports, so one reporter cannot flood the webhook. */
    private final java.util.Map<String, Deque<Long>> recentReports = new java.util.HashMap<>();

    private long handled;
    private long ignored;
    private long rateLimited;
    private long denied;

    public AssistantRuntime(AssistantMemory memory) {
        this.memory = memory == null ? new AssistantMemory() : memory;
        this.brain = new AssistantBrain(this.memory);
    }

    public AssistantMemory memory() {
        return memory;
    }

    public AssistantBrain brain() {
        return brain;
    }

    public ChatActivity activity() {
        return activity;
    }

    /** Replaces the rules and drops any cooldown state for patterns that no longer exist. */
    public void setTriggers(TriggerSet triggers) {
        ReactionGate current = this.triggers == null ? null : this.triggers.gate();
        this.triggers = triggers == null ? TriggerSet.empty() : triggers;
        // A new rule list must not silently reopen a gate the user closed, so the existing gate is
        // carried across. Rebuilding the rules is a separate event from changing the switches.
        if (current != null && this.triggers.gate() != current) {
            this.triggers = this.triggers.withGate(current);
        }
        cooldowns.retain(this.triggers.rules().stream().map(TriggerRule::pattern).toList());
    }

    public TriggerSet triggers() {
        return triggers;
    }

    /** The diagnostic record of recent reaction decisions. */
    public ReactionLog reactionLog() {
        return reactionLog;
    }

    /**
     * Replaces the gate every trigger is filtered through.
     *
     * <p>Applied to the set rather than kept beside it, so the gate travels with the rules into every
     * path that matches them — including the ones that only hold a {@link TriggerSet}.
     */
    public void setGate(ReactionGate gate) {
        this.triggers = this.triggers.withGate(gate);
    }

    /** The gate currently in force. */
    public ReactionGate gate() {
        return triggers.gate();
    }

    public void setConfig(AssistantConfig config) {
        this.config = config == null ? AssistantConfig.DEFAULTS : config;
    }

    public AssistantConfig config() {
        return config;
    }

    /** Turns the duplicate filter on or off; off means every repeated line is answered again. */
    public void setDuplicateFilter(boolean enabled) {
        this.duplicateFilterEnabled = enabled;
        if (!enabled) duplicates.reset();
    }

    /** Turns the help command on or off. Off makes it answer nothing at all. */
    public void setHelpEnabled(boolean enabled) {
        this.helpEnabled = enabled;
    }

    public boolean helpEnabled() {
        return helpEnabled;
    }

    /** Forwards the per-command table to the brain; null restores the shipped defaults. */
    public void setCommandDefaults(java.util.Map<String, CommandDefaults.Entry> entries) {
        brain.setCommandDefaults(entries);
    }

    /** Forwards the greeting pools to the brain, so the hello command uses the user's wording. */
    public void setGreetingPools(java.util.Map<String, String> pools) {
        brain.setGreetingPools(pools);
    }

    /** Forwards the configured good-game reply to the brain. */
    public void setGoodGameReply(String reply) {
        brain.setGoodGameReply(reply);
    }

    public void setPermissions(PermissionSet permissions) {
        this.permissions = permissions == null ? PermissionSet.permissive() : permissions;
    }

    public PermissionSet permissions() {
        return permissions;
    }

    /**
     * Evaluates one message, applying the per-rule cooldown and the global reply rate limit.
     *
     * @return the decision, or {@link AssistantBrain.Decision#NONE} when nothing should happen
     */
    public AssistantBrain.Decision handle(String sender, String content, long now) {
        return handle(SenderIdentity.unverified(sender), content, now, ServerStats.OFFLINE, null);
    }

    /**
     * Evaluates one message with live server figures available to the stats commands.
     *
     * @param stats        the current snapshot, or {@link ServerStats#OFFLINE}
     * @param statusSettings which status commands may answer, or null for the defaults
     */
    public AssistantBrain.Decision handle(String sender, String content, long now,
                                          ServerStats stats, StatusCommands.Settings statusSettings) {
        return handle(SenderIdentity.unverified(sender), content, now, stats, statusSettings);
    }

    /**
     * Evaluates one message from a known sender.
     *
     * <p>The identity is what the client could verify, not what the message claimed. Permission
     * decisions use it, so this is the overload the module calls.
     */
    public AssistantBrain.Decision handle(SenderIdentity identity, String content, long now,
                                          ServerStats stats, StatusCommands.Settings statusSettings) {
        return handle(identity, content, now, stats, statusSettings, -1);
    }

    /**
     * Evaluates one message from a sender known only by name, with the speaker's own latency.
     *
     * <p>The name-based form exists for callers that only have a chat line. It carries the
     * requester's figure as well so a test or a log replay can answer {@code !ping} about the
     * speaker without reaching into {@link SenderIdentity}.
     */
    public AssistantBrain.Decision handle(String sender, String content, long now,
                                          ServerStats stats, StatusCommands.Settings statusSettings,
                                          int requesterPing) {
        return handle(SenderIdentity.unverified(sender), content, now, stats, statusSettings,
            requesterPing);
    }

    /**
     * Evaluates one message, with the speaker's own latency.
     *
     * <p>Only the module can read a player's latency from the tab list, so it is passed in rather
     * than looked up here; the runtime stays free of Minecraft types.
     *
     * @param requesterPing the speaker's latency in milliseconds, or a negative value when unknown
     */
    public AssistantBrain.Decision handle(SenderIdentity identity, String content, long now,
                                          ServerStats stats, StatusCommands.Settings statusSettings,
                                          int requesterPing) {
        if (content == null || content.isBlank()) {
            ignored++;
            return AssistantBrain.Decision.NONE;
        }
        SenderIdentity sender = identity == null ? SenderIdentity.unverified("") : identity;
        // Callers without a world pass null; every figure then reports as absent rather than
        // throwing, so a trigger can still be evaluated off-line (and in a test).
        ServerStats liveStats = stats == null ? ServerStats.OFFLINE : stats;
        activity.observe(sender.name(), now);

        if (duplicateFilterEnabled && !duplicates.accept(sender.name(), content, now)) {
            activity.recordSuppressed();
            ignored++;
            return AssistantBrain.Decision.NONE;
        }

        AssistantConfig cfg = config;
        // The command path and the trigger path must agree on whose latency {ping} reports. A caller
        // that could not read the figure passes a negative value; for the local player the client's
        // own reading is that figure, so resolve it once here rather than in each path.
        int speakerPing = requesterPing >= 0 || !sender.local() ? requesterPing : liveStats.latency();
        // A bare-word trigger must not swallow a prefixed command: with the shipped table, a trigger
        // for "hello" would answer "!hello" before the command parser ever saw it, making the command
        // unreachable. A trigger the user wrote *with* the prefix ("!remember") is deliberate, so it
        // is still allowed to win — that escape hatch is what the precedence test pins.
        String prefix = cfg.commandPrefix() == null ? "" : cfg.commandPrefix();
        boolean addressedToAssistant = !prefix.isBlank() && content.strip().startsWith(prefix);
        java.util.function.Predicate<TriggerRule> allowed = addressedToAssistant
            ? rule -> rule.pattern().strip().startsWith(prefix)
            : null;
        TriggerRule rule = null;
        if (cfg.respondToTriggers()) {
            TriggerSet.Match match =
                triggers.firstMatchExplained(sender.name(), content, allowed, now);
            rule = match.rule();
            if (rule != null && !match.allowed()) {
                // Matched but refused. Recorded rather than silently dropped: this is the case that
                // used to be indistinguishable from "the rule does not exist", which is what made
                // the reported bug hard to diagnose.
                reactionLog.record(now, sender.name(), content, ReactionLog.Outcome.SKIPPED,
                    rule, match.reason());
                ignored++;
                return AssistantBrain.Decision.NONE;
            }
        }

        if (rule != null) {
            // A trigger is the assistant speaking unprompted, so who may fire one is a separate
            // decision from who may run a command. Ordinary chatter ("gg", "ty") matches the shipped
            // table constantly, so answering strangers by default is noise, not a feature. A line
            // from someone out of earshot is ignored, not refused: nothing was asked of the assistant.
            if (!cfg.triggerAudience().allows(sender, cfg.triggerAllowList())) {
                ignored++;
                return AssistantBrain.Decision.NONE;
            }
            // A rule whose reply is a server command is gated by who is asking: a state-changing
            // command may only run for the local player, so a stranger cannot type a trigger word
            // and make this client run /tpaccept or /pay. A message-carrying command is safe.
            if (!rule.allowsCommandFrom(sender.local())) {
                denied++;
                return AssistantBrain.Decision.NONE;
            }
            if (!cooldowns.tryFire(rule, sender.name(), now)) {
                ignored++;
                return AssistantBrain.Decision.NONE;
            }
            // A reply built from the operator's position, gear or memory describes this client, not
            // the person who asked. A trigger is answered for whoever typed it, so without this
            // check a stranger typing "where am i" would be handed the operator's coordinates - the
            // command path already refuses exactly that, and the two paths must agree.
            if (!sender.local() && Placeholders.mentionsPrivateFigure(rule.reply())) {
                denied++;
                return AssistantBrain.Decision.refusal(rule.mode(),
                    "That answer describes the local player, so only they can ask for it.");
            }
            if (!allowGlobalReply(cfg, now)) {
                rateLimited++;
                return AssistantBrain.Decision.NONE;
            }
            handled++;
            AssistantBrain.Decision decision =
                brain.trigger(rule, sender.name(), content, cfg, now, liveStats, speakerPing);
            if (decision.acts()) {
                activity.recordReply();
                reactionLog.record(now, sender.name(), content, ReactionLog.Outcome.REPLIED,
                    rule, "");
            }
            return decision;
        }

        if (cfg.memoryCommands()) {
            // Only the command parser runs here: the trigger path already declined, and a command
            // reply is counted separately from a trigger reply.
            AssistantBrain.Decision command =
                brain.command(sender, content, cfg, now, liveStats, statusSettings, permissions,
                    activity, helpEnabled, speakerPing);
            if (command != null && command.acts()) {
                if (command.denied()) {
                    // A refusal gets its own budget. Charging it to the shared budget would let any
                    // player silence the assistant by repeatedly using a command they may not use.
                    if (!allowDenialReply(now)) {
                        rateLimited++;
                        return AssistantBrain.Decision.NONE;
                    }
                    denied++;
                } else if (!allowGlobalReply(cfg, now)) {
                    rateLimited++;
                    return AssistantBrain.Decision.NONE;
                }
                if (command.hasReport() && !allowReport(sender.name(), now)) {
                    rateLimited++;
                    return AssistantBrain.Decision.NONE;
                }
                handled++;
                activity.recordReply();
                return command;
            }
        }
        ignored++;
        return AssistantBrain.Decision.NONE;
    }

    /**
     * The report budget for one player, kept per player and separate from the reply budget.
     *
     * <p>A report leaves the game for Discord, so it needs its own cap: without one, a single player
     * could fill a moderator channel by repeating {@code !report}. Per player rather than global,
     * because one player's spam must not stop everyone else from reporting.
     */
    public boolean allowReport(String reporter, long now) {
        String key = reporter == null || reporter.isBlank() ? "" : reporter.strip().toLowerCase(java.util.Locale.ROOT);
        Deque<Long> recent = recentReports.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() > RATE_WINDOW) recent.pollFirst();
        if (recent.size() >= REPORTS_PER_MINUTE) return false;
        recent.addLast(now);
        return true;
    }

    /** True when the global per-minute reply budget allows another reply at {@code now}. */
    public boolean allowGlobalReply(AssistantConfig cfg, long now) {
        int limit = cfg == null ? 0 : cfg.globalRateLimitPerMinute();
        if (limit <= 0) return true;
        while (!recentReplies.isEmpty() && now - recentReplies.peekFirst() > RATE_WINDOW) {
            recentReplies.pollFirst();
        }
        if (recentReplies.size() >= limit) return false;
        recentReplies.addLast(now);
        return true;
    }

    /**
     * The budget for refusals, kept separate from {@link #allowGlobalReply}.
     *
     * <p>A refusal is still a reply, so it needs a cap — otherwise a stranger could make the
     * assistant answer every line. But it must not share the budget with real answers: if it did,
     * anyone could exhaust the minute by repeating a command they are not allowed to use and
     * silence the assistant for the people who are allowed to use it.
     */
    private boolean allowDenialReply(long now) {
        while (!recentDenials.isEmpty() && now - recentDenials.peekFirst() > RATE_WINDOW) {
            recentDenials.pollFirst();
        }
        if (recentDenials.size() >= DENIALS_PER_MINUTE) return false;
        recentDenials.addLast(now);
        return true;
    }

    public long handled() {
        return handled;
    }

    public long ignored() {
        return ignored;
    }

    public long rateLimited() {
        return rateLimited;
    }

    /** How many commands the permission gate refused. */
    public long denied() {
        return denied;
    }

    /** Clears session state (cooldowns, rate window, activity) but keeps the persistent memory. */
    public void resetSession() {
        cooldowns.reset();
        duplicates.reset();
        recentReplies.clear();
        recentDenials.clear();
        recentReports.clear();
        activity.reset();
        handled = 0;
        ignored = 0;
        rateLimited = 0;
        denied = 0;
    }

    /** A one-line summary for the HUD. */
    public String statusLine() {
        return triggers.rules().size() + " triggers, " + memory.facts().size() + " facts, "
            + handled + " handled";
    }
}
