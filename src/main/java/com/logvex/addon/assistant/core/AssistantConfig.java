package com.logvex.addon.assistant.core;

/**
 * An immutable snapshot of every tunable the assistant's decision logic reads.
 *
 * <p>The module builds one of these from its settings each time they change, so the core never
 * reaches back into Minecraft and a test can construct an exact configuration.
 *
 * <p>Every reply the assistant makes is chosen by a trigger rule or a command, so this record has no
 * confidence figure: there is nothing to be unsure about.
 */
public record AssistantConfig(
    String name,
    boolean respondToTriggers,
    boolean memoryCommands,
    String commandPrefix,
    TriggerRule.ReplyMode memoryReplyMode,
    boolean rememberConversations,
    int globalRateLimitPerMinute,
    int maxReplyLength,
    TriggerAudience triggerAudience,
    java.util.Set<String> triggerAllowList
) {
    public static final AssistantConfig DEFAULTS = new AssistantConfig(
        "Assistant", true, true, "!", TriggerRule.ReplyMode.NOTIFY, true, 20, 200,
        TriggerAudience.ALLOWLIST, java.util.Set.of());

    public AssistantConfig {
        if (name == null || name.isBlank()) name = "Assistant";
        if (commandPrefix == null || commandPrefix.isBlank()) commandPrefix = "!";
        if (memoryReplyMode == null) memoryReplyMode = TriggerRule.ReplyMode.NOTIFY;
        if (globalRateLimitPerMinute < 0) globalRateLimitPerMinute = 0;
        if (maxReplyLength <= 0) maxReplyLength = 200;
        if (triggerAudience == null) triggerAudience = TriggerAudience.ALLOWLIST;
        triggerAllowList = triggerAllowList == null
            ? java.util.Set.of()
            : java.util.Set.copyOf(triggerAllowList);
    }

    /**
     * A config with every earlier field and the default audience.
     *
     * <p>Kept so callers written before the audience existed keep compiling; they get the safe
     * default rather than the old "answer everyone" behaviour.
     */
    public AssistantConfig(String name, boolean respondToTriggers, boolean memoryCommands,
                           String commandPrefix, TriggerRule.ReplyMode memoryReplyMode,
                           boolean rememberConversations, int globalRateLimitPerMinute,
                           int maxReplyLength) {
        this(name, respondToTriggers, memoryCommands, commandPrefix, memoryReplyMode,
            rememberConversations, globalRateLimitPerMinute, maxReplyLength,
            TriggerAudience.ALLOWLIST, java.util.Set.of());
    }

    /** A config for callers that only use triggers. */
    public static AssistantConfig basic(String name, boolean respondToTriggers, boolean memoryCommands,
                                        String commandPrefix, TriggerRule.ReplyMode memoryReplyMode,
                                        boolean rememberConversations, int globalRateLimitPerMinute,
                                        int maxReplyLength) {
        return new AssistantConfig(name, respondToTriggers, memoryCommands, commandPrefix,
            memoryReplyMode, rememberConversations, globalRateLimitPerMinute, maxReplyLength);
    }

    /** The same config with a different trigger audience, for callers that only change that. */
    public AssistantConfig withAudience(TriggerAudience audience) {
        return new AssistantConfig(name, respondToTriggers, memoryCommands, commandPrefix,
            memoryReplyMode, rememberConversations, globalRateLimitPerMinute, maxReplyLength,
            audience, triggerAllowList);
    }

    /** The same config with a different audience and allowlist. */
    public AssistantConfig withAudience(TriggerAudience audience, java.util.Set<String> allowList) {
        return new AssistantConfig(name, respondToTriggers, memoryCommands, commandPrefix,
            memoryReplyMode, rememberConversations, globalRateLimitPerMinute, maxReplyLength,
            audience, allowList);
    }
}
