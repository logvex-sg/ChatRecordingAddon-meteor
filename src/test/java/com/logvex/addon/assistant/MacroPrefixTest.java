package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.ChatMacro;
import com.logvex.addon.assistant.core.MacroSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Macros must keep working when the command prefix changes.
 *
 * <p>A macro is stored as the user typed it, prefix included. Matching compared the whole spelling,
 * so changing {@code !} to {@code #} left every stored shortcut unmatched and silently disabled the
 * entire table. Comparison is now on the bare word.
 */
class MacroPrefixTest {

    private static List<String> stored() {
        return ChatMacro.defaults().stream().map(ChatMacro::encode).toList();
    }

    @Test
    @DisplayName("every default macro resolves under any configured prefix")
    void everyMacroSurvivesAPrefixChange() {
        for (String prefix : List.of("!", "#", ".", ":")) {
            MacroSet set = MacroSet.decode(stored(), prefix);
            for (ChatMacro macro : ChatMacro.defaults()) {
                String typed = prefix + macro.shortcut().substring(1);
                assertTrue(set.resolve(typed).isPresent(),
                    "'" + typed + "' no longer resolves with prefix '" + prefix + "'");
            }
        }
    }

    @Test
    @DisplayName("a macro runs to the right command under a changed prefix")
    void macroRunsUnderChangedPrefix() {
        MacroSet set = MacroSet.decode(stored(), "#");
        MacroSet.Call call = set.resolve("#tpa Steve").orElseThrow();
        assertEquals("/tpa Steve", call.macro().run(call.input()).command());
    }

    @Test
    @DisplayName("ordinary chat is still not a macro")
    void ordinaryChatIsNotAMacro() {
        MacroSet set = MacroSet.decode(stored(), "#");
        assertTrue(set.resolve("hello there").isEmpty());
        assertTrue(set.resolve("#notamacro").isEmpty());
    }

    @Test
    @DisplayName("no two default macros claim the same bare spelling")
    void defaultsHaveNoDuplicateSpellings() {
        List<String> duplicates = MacroSet.decode(stored(), "!").duplicates();
        assertTrue(duplicates.isEmpty(), "duplicate macro spellings: " + duplicates);
    }
}
