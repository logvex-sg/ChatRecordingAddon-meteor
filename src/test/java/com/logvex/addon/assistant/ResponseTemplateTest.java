package com.logvex.addon.assistant;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.ResponseTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reply templates: placeholder expansion and tolerance of unknown placeholders. */
class ResponseTemplateTest {

    private ResponseTemplate.Context context(AssistantMemory memory) {
        return new ResponseTemplate.Context("Notch", "hello there", "mc.example.net", memory, 0L);
    }

    @Test
    @DisplayName("player, message and server placeholders expand")
    void basicPlaceholders() {
        ResponseTemplate.Context ctx = context(new AssistantMemory());
        assertEquals("Hi Notch!", ResponseTemplate.expand("Hi {player}!", ctx));
        assertEquals("you said: hello there", ResponseTemplate.expand("you said: {message}", ctx));
        assertEquals("on mc.example.net", ResponseTemplate.expand("on {server}", ctx));
    }

    @Test
    @DisplayName("a fact placeholder expands to the stored value or empty")
    void factPlaceholder() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("home", "the castle", 1L);
        ResponseTemplate.Context ctx = context(memory);

        assertEquals("home is the castle", ResponseTemplate.expand("home is {fact:home}", ctx));
        assertEquals("home is ", ResponseTemplate.expand("home is {fact:missing}", ctx));
    }

    @Test
    @DisplayName("a counter placeholder expands to the stored count or zero")
    void counterPlaceholder() {
        AssistantMemory memory = new AssistantMemory();
        memory.incrementCounter("replies", 7);
        ResponseTemplate.Context ctx = context(memory);

        assertEquals("7 replies", ResponseTemplate.expand("{count:replies} replies", ctx));
        assertEquals("0 replies", ResponseTemplate.expand("{count:none} replies", ctx));
    }

    @Test
    @DisplayName("the memory placeholder expands to the fact count")
    void memoryPlaceholder() {
        AssistantMemory memory = new AssistantMemory();
        memory.rememberFact("a", "1", 1L);
        memory.rememberFact("b", "2", 2L);
        assertEquals("2 facts", ResponseTemplate.expand("{memory} facts", context(memory)));
    }

    @Test
    @DisplayName("an unknown placeholder is left verbatim and never throws")
    void unknownPlaceholderPreserved() {
        ResponseTemplate.Context ctx = context(new AssistantMemory());
        assertEquals("keep {nonsense} as is", ResponseTemplate.expand("keep {nonsense} as is", ctx));
        assertEquals("dangling {", ResponseTemplate.expand("dangling {", ctx));
        assertEquals("literal {}", ResponseTemplate.expand("literal {}", ctx));
    }

    @Test
    @DisplayName("a null or empty template yields an empty string")
    void nullSafe() {
        assertEquals("", ResponseTemplate.expand(null, context(new AssistantMemory())));
        assertEquals("", ResponseTemplate.expand("", context(new AssistantMemory())));
    }
}
