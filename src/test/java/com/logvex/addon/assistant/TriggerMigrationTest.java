package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.TriggerRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A saved rule list must pick up commands shipped after it was written.
 *
 * <p>The rules live in a persisted setting, so an install that predates a command keeps the old
 * list forever and the new command is unreachable — which is what "you removed !cmds" looks like
 * from the player's side.
 */
class TriggerMigrationTest {

    private static List<String> encodedWithout(String pattern) {
        return TriggerRule.preconfigured().stream()
            .filter(r -> !r.pattern().equalsIgnoreCase(pattern))
            .map(TriggerRule::encode)
            .toList();
    }

    @Test
    void aSavedListGainsCommandsShippedLater() {
        List<String> stale = encodedWithout("!cmds");

        List<String> merged = TriggerRule.mergeWithPreconfigured(stale);

        assertTrue(merged.stream().map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(s -> s.startsWith("!cmds;")),
            "the saved list never gained !cmds: " + merged.size() + " rules");
    }

    @Test
    void aSavedListKeepsTheUsersOwnEdits() {
        List<String> stale = new java.util.ArrayList<>(encodedWithout("!cmds"));
        // A rule the user wrote themselves must survive the merge untouched.
        String custom = new TriggerRule("!mycmd", false, false, "", "custom answer",
            TriggerRule.ReplyMode.PRIVATE, 0L, 0, false, false, true, "", "",
            TriggerRule.DEFAULT_WHISPER).encode();
        stale.add(custom);

        List<String> merged = TriggerRule.mergeWithPreconfigured(stale);

        assertTrue(merged.contains(custom), "a hand-written rule was dropped");
        // Everything the shipped table has is now present, and nothing was duplicated.
        assertEquals(stale.size() + countShippedMissing(stale), merged.size());
        assertEquals(merged.size(), new java.util.HashSet<>(merged).size(), "a rule was duplicated");
    }

    private static long countShippedMissing(List<String> existing) {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String line : existing) {
            TriggerRule rule = TriggerRule.decode(line);
            have.add(rule.pattern().toLowerCase(Locale.ROOT));
        }
        return TriggerRule.preconfigured().stream()
            .filter(r -> !have.contains(r.pattern().toLowerCase(Locale.ROOT)))
            .count();
    }
}
