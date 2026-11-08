package com.logvex.addon.assistant.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The live assistant state: memory, rules, configuration and the rate limits, with no Minecraft
 * dependency.
 *
 * <p>The module owns one of these and drives it from the event bus. Keeping the state here rather
 * than in the module means the whole decision path — including the global rate limit and the
 * per-rule cooldown — can be exercised in a unit test with no game running.
 */
public final class AssistantRuntime {
    /** How many replies the global rate limiter looks at. */
    private static final int RATE_WINDOW = 60_000;

    private final AssistantMemory memory;
    private final AssistantBrain brain;
    private final TriggerCooldownTracker cooldowns = new TriggerCooldownTracker();

    private TriggerSet triggers = TriggerSet.empty();
    private AssistantConfig config = AssistantConfig.DEFAULTS;

    /** Timestamps of recent replies, used for the global per-minute rate limit. */
    private final Deque<Long> recentReplies = new ArrayDeque<>();

    private long handled;
    private long ignored;
    private long rateLimited;
    private long aiReplies;

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

    /** Replaces the rules and drops any cooldown state for patterns that no longer exist. */
    public void setTriggers(TriggerSet triggers) {
        this.triggers = triggers == null ? TriggerSet.empty() : triggers;
        cooldowns.retain(this.triggers.rules().stream().map(TriggerRule::pattern).toList());
    }

    public TriggerSet triggers() {
        return triggers;
    }

    public void setConfig(AssistantConfig config) {
        this.config = config == null ? AssistantConfig.DEFAULTS : config;
    }

    public AssistantConfig config() {
        return config;
    }

    /**
     * Evaluates one message, applying the per-rule cooldown and the global reply rate limit.
     *
     * @return the decision, or {@link AssistantBrain.Decision#NONE} when nothing should happen
     */
    public AssistantBrain.Decision handle(String sender, String content, long now) {
        if (content == null || content.isBlank()) {
            ignored++;
            return AssistantBrain.Decision.NONE;
        }

        AssistantConfig cfg = config;
        TriggerRule rule = cfg.respondToTriggers() ? triggers.firstMatch(sender, content) : null;

        if (rule != null) {
            if (!cooldowns.tryFire(rule, now)) {
                ignored++;
                return AssistantBrain.Decision.NONE;
            }
            if (!allowGlobalReply(cfg, now)) {
                rateLimited++;
                return AssistantBrain.Decision.NONE;
            }
            handled++;
            return brain.trigger(rule, sender, content, cfg, now);
        }

        if (cfg.memoryCommands()) {
            // Only the command parser runs here: the trigger path already declined, and the AI must
            // not be reached from this branch or its reply would be counted as a command.
            AssistantBrain.Decision command = brain.command(sender, content, cfg, now);
            if (command != null && command.acts()) {
                if (!allowGlobalReply(cfg, now)) {
                    rateLimited++;
                    return AssistantBrain.Decision.NONE;
                }
                handled++;
                return command;
            }
        }
        if (cfg.localAi()) {
            AssistantBrain.Decision ai = brain.ai(sender, content, cfg, now);
            if (ai != null && ai.acts()) {
                if (!allowGlobalReply(cfg, now)) {
                    rateLimited++;
                    return AssistantBrain.Decision.NONE;
                }
                handled++;
                aiReplies++;
                return ai;
            }
        }
        ignored++;
        return AssistantBrain.Decision.NONE;
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

    public long handled() {
        return handled;
    }

    public long ignored() {
        return ignored;
    }

    public long rateLimited() {
        return rateLimited;
    }

    /** How many of the handled messages were answered by the local AI. */
    public long aiReplies() {
        return aiReplies;
    }

    /** Clears session state (cooldowns, rate window) but keeps the persistent memory. */
    public void resetSession() {
        cooldowns.reset();
        recentReplies.clear();
        handled = 0;
        ignored = 0;
        rateLimited = 0;
        aiReplies = 0;
    }

    /** A one-line summary for the HUD. */
    public String statusLine() {
        return triggers.rules().size() + " triggers, " + memory.facts().size() + " facts, "
            + handled + " handled";
    }
}
