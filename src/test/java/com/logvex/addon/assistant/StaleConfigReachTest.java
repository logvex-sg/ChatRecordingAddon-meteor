package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.*;
import com.logvex.addon.stats.ServerStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end: a config saved before {@code !cmds} existed must reach it after loading.
 *
 * <p>This is the user-visible symptom. The command is in the build and renders correctly, but the
 * rule table is a persisted setting, so an old config answered nothing and the command looked
 * removed.
 */
class StaleConfigReachTest {

    @Test
    void anOldSavedRuleListStillAnswersCmds() {
        List<String> stale = TriggerRule.preconfigured().stream()
            .filter(r -> !r.pattern().startsWith("!cmds"))
            .map(TriggerRule::encode)
            .toList();

        // The same call AssistantModule.parseRules makes at load.
        TriggerSet loaded = TriggerSet.of(TriggerRule.loadSaved(stale));

        AssistantRuntime rt = new AssistantRuntime(new AssistantMemory());
        rt.setTriggers(loaded);
        rt.brain().setTriggerSet(loaded);
        rt.setConfig(new AssistantConfig("Log", true, true, "!", TriggerRule.ReplyMode.PRIVATE, true, 0, 256));
        rt.setDuplicateFilter(false);

        AssistantBrain.Decision d = rt.handle(SenderIdentity.local("Me", null), "!cmds", 1000L,
            ServerStats.OFFLINE, null);

        assertNotEquals(AssistantBrain.Decision.NONE, d, "a stale config still answers nothing");
        assertTrue(d.linesCapped(48, null, 12).stream().anyMatch(l -> l.contains("!cmds1")),
            "the index did not render its pages");
    }
}
