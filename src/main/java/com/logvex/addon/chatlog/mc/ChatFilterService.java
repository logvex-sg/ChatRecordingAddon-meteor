package com.logvex.addon.chatlog.mc;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.ChatFilterRuleSet;
import com.logvex.addon.framework.events.game.ReceiveMessageEvent;
import com.logvex.addon.framework.orbit.EventBus;
import com.logvex.addon.framework.orbit.EventHandler;

/**
 * Applies the chat filter to the live chat stream.
 *
 * <p>It subscribes once, independently of any module, for the same reason {@link ChatRecorder} does:
 * a filter that only worked while a module was enabled would leak messages on toggle. The active
 * {@link ChatFilterRuleSet} is swapped atomically, so a decision never sees a half-edited rule list.
 *
 * <p>Suppression works by cancelling the message before it is stored — see the mixin — so a filtered
 * message never reaches the chat HUD, the recorder or the log file.
 */
public final class ChatFilterService {
    private static final ChatFilterService INSTANCE = new ChatFilterService();

    public static ChatFilterService get() {
        return INSTANCE;
    }

    private volatile ChatFilterRuleSet rules = ChatFilterRuleSet.empty();
    private volatile boolean enabled;
    private volatile long hiddenCount;
    private volatile long lastHiddenAt;
    private volatile String lastHidden = "";

    private ChatFilterService() {
    }

    /** Subscribes to the live chat stream. Called once from the addon bootstrap. */
    public void init(EventBus bus) {
        if (bus != null) bus.subscribe(this);
    }

    /** Replaces the active rule set. Pass {@link ChatFilterRuleSet#empty()} to disable filtering. */
    public void setRules(ChatFilterRuleSet rules) {
        this.rules = rules == null ? ChatFilterRuleSet.empty() : rules;
    }

    public ChatFilterRuleSet rules() {
        return rules;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled && !rules.isEmpty();
    }

    public long hiddenCount() {
        return hiddenCount;
    }

    public long lastHiddenAt() {
        return lastHiddenAt;
    }

    /** A short, secret-free description of the most recently hidden message. */
    public String lastHidden() {
        return lastHidden;
    }

    public void resetStats() {
        hiddenCount = 0;
        lastHiddenAt = 0;
        lastHidden = "";
    }

    /**
     * Decides whether {@code event} should be suppressed, recording the reason when it is.
     *
     * @return true when the message should be removed before it is displayed
     */
    public boolean shouldHide(ReceiveMessageEvent event) {
        if (!isEnabled() || event == null || event.getMessage() == null) return false;

        String content = event.getMessage().getString();
        if (content == null || content.isBlank()) return false;

        ChatEntry entry = new ChatEntry(System.currentTimeMillis(),
            com.logvex.addon.chatlog.core.ChatClassifier.classify(event.getIndicator() != null, content).type(),
            "", content, "", "");

        if (!rules.hides(entry)) return false;

        hiddenCount++;
        lastHiddenAt = entry.timestamp();
        lastHidden = com.logvex.addon.common.Messages.truncate(content, 60);
        return true;
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        // The mixin calls shouldHide directly (it needs the answer synchronously); this handler is
        // kept so the service is subscribed even if the mixin is unavailable, and so the bus shows
        // the dependency. It deliberately does not cancel anything.
    }
}
