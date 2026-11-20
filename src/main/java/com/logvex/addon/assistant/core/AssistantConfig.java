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
    int maxReplyLength
) {
    public static final AssistantConfig DEFAULTS = new AssistantConfig(
        "Assistant", true, true, "!", TriggerRule.ReplyMode.NOTIFY, true, 20, 200);

    public AssistantConfig {
        if (name == null || name.isBlank()) name = "Assistant";
        if (commandPrefix == null || commandPrefix.isBlank()) commandPrefix = "!";
        if (memoryReplyMode == null) memoryReplyMode = TriggerRule.ReplyMode.NOTIFY;
        if (globalRateLimitPerMinute < 0) globalRateLimitPerMinute = 0;
        if (maxReplyLength <= 0) maxReplyLength = 200;
    }

    /** A config for callers that only use triggers. */
    public static AssistantConfig basic(String name, boolean respondToTriggers, boolean memoryCommands,
                                        String commandPrefix, TriggerRule.ReplyMode memoryReplyMode,
                                        boolean rememberConversations, int globalRateLimitPerMinute,
                                        int maxReplyLength) {
        return new AssistantConfig(name, respondToTriggers, memoryCommands, commandPrefix,
            memoryReplyMode, rememberConversations, globalRateLimitPerMinute, maxReplyLength);
    }
}
