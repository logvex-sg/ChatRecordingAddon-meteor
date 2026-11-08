package com.logvex.addon.assistant.core;

/**
 * An immutable snapshot of every tunable the assistant's decision logic reads.
 *
 * <p>The module builds one of these from its settings each time they change, so the core never
 * reaches back into Minecraft and a test can construct an exact configuration.
 *
 * <p>The last four fields drive the local AI: whether it answers at all, how confident it must be
 * before it speaks, how its replies are delivered, and whether it may learn new phrasings from chat.
 * They are kept together because they are only meaningful as a group — a threshold with the AI off
 * does nothing.
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
    boolean localAi,
    double aiThreshold,
    TriggerRule.ReplyMode aiReplyMode,
    boolean aiLearnFromChat
) {
    public static final AssistantConfig DEFAULTS = new AssistantConfig(
        "Assistant", true, true, "!", TriggerRule.ReplyMode.NOTIFY, true, 20, 200,
        false, 0.45, TriggerRule.ReplyMode.CHAT, false);

    public AssistantConfig {
        if (name == null || name.isBlank()) name = "Assistant";
        if (commandPrefix == null || commandPrefix.isBlank()) commandPrefix = "!";
        if (memoryReplyMode == null) memoryReplyMode = TriggerRule.ReplyMode.NOTIFY;
        if (globalRateLimitPerMinute < 0) globalRateLimitPerMinute = 0;
        if (maxReplyLength <= 0) maxReplyLength = 200;
        if (aiReplyMode == null) aiReplyMode = TriggerRule.ReplyMode.CHAT;
        if (aiThreshold < 0.05) aiThreshold = 0.05;
        if (aiThreshold > 0.95) aiThreshold = 0.95;
    }

    /** A copy with a different AI threshold, so the GUI can preview a value without a rebuild. */
    public AssistantConfig withThreshold(double threshold) {
        return new AssistantConfig(name, respondToTriggers, memoryCommands, commandPrefix,
            memoryReplyMode, rememberConversations, globalRateLimitPerMinute, maxReplyLength,
            localAi, threshold, aiReplyMode, aiLearnFromChat);
    }

    /** A config with the local AI left off; the common case for callers that only use triggers. */
    public static AssistantConfig basic(String name, boolean respondToTriggers, boolean memoryCommands,
                                        String commandPrefix, TriggerRule.ReplyMode memoryReplyMode,
                                        boolean rememberConversations, int globalRateLimitPerMinute,
                                        int maxReplyLength) {
        return new AssistantConfig(name, respondToTriggers, memoryCommands, commandPrefix,
            memoryReplyMode, rememberConversations, globalRateLimitPerMinute, maxReplyLength,
            false, 0.45, TriggerRule.ReplyMode.CHAT, false);
    }
}
