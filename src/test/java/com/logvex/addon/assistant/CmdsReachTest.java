package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.*;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Every page the index advertises must actually answer. */
class CmdsReachTest {
    private static AssistantBrain.Decision ask(String q) {
        AssistantRuntime rt = new AssistantRuntime(new AssistantMemory());
        TriggerSet set = TriggerSet.of(TriggerRule.preconfigured());
        rt.setTriggers(set);
        rt.brain().setTriggerSet(set);
        rt.setConfig(new AssistantConfig("Log", true, true, "!", TriggerRule.ReplyMode.PRIVATE, true, 0, 256));
        rt.setDuplicateFilter(false);
        return rt.handle(SenderIdentity.local("Me", null), q, 1000L, ServerStats.OFFLINE, null);
    }

    @Test
    void everyAdvertisedPageAnswers() {
        for (int page = 1; page <= 10; page++) {
            AssistantBrain.Decision d = ask("!cmds" + page);
            assertNotEquals(AssistantBrain.Decision.NONE, d, "!cmds" + page + " answered nothing");
            assertFalse(d.linesCapped(48, null, 12).isEmpty(), "!cmds" + page + " rendered nothing");
        }
    }
}
