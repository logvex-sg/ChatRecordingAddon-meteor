package com.logvex.addon.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every event handler must run its body through {@link SafeEventHandler}.
 *
 * <p>Meteor posts events from inside vanilla methods that cannot survive a listener throwing —
 * {@code disconnect()} above all — and its bus (orbit) calls listeners with no try/catch. One
 * unguarded throw is therefore a client crash, not a logged error. This test walks the real source
 * tree so a handler added without the wrapper fails the build instead of the game.
 */
class SafeEventHandlerAuditTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    @DisplayName("no event handler in the source tree runs unguarded")
    void everyHandlerIsGuarded() throws IOException {
        List<HandlerAudit.Finding> findings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                findings.addAll(HandlerAudit.unguarded(
                    SOURCE_ROOT.relativize(file).toString(), Files.readString(file)));
            }
        }

        assertTrue(findings.isEmpty(),
            () -> "These handlers would crash the client if they threw; wrap each body in "
                + "SafeEventHandler.guard(\"Class.method\", () -> { ... }).run():\n  "
                + String.join("\n  ", findings.stream().map(Object::toString).toList()));
    }

    @Test
    @DisplayName("the audit finds a handler with no guard")
    void detectsUnguarded() {
        String source = """
            class A {
                @EventHandler
                private void onTick(TickEvent.Post event) {
                    doWork();
                }
            }
            """;

        List<HandlerAudit.Finding> findings = HandlerAudit.unguarded("A.java", source);

        assertEquals(1, findings.size());
        assertEquals("onTick", findings.get(0).handler());
        assertEquals(2, findings.get(0).line(), "the annotation line is reported");
    }

    @Test
    @DisplayName("the audit accepts a guarded handler")
    void acceptsGuarded() {
        String source = """
            class A {
                @EventHandler
                private void onTick(TickEvent.Post event) {
                    SafeEventHandler.guard("A.onTick", () -> {
                        doWork();
                    }).run();
                }
            }
            """;

        assertTrue(HandlerAudit.unguarded("A.java", source).isEmpty());
    }

    @Test
    @DisplayName("a guarded handler followed by an unguarded one reports only the second")
    void reportsOnlyTheUnguarded() {
        String source = """
            class A {
                @EventHandler
                private void first(TickEvent.Post event) {
                    SafeEventHandler.run("A.first", this::work);
                }

                @EventHandler
                private void second(GameLeftEvent event) {
                    work();
                }
            }
            """;

        List<HandlerAudit.Finding> findings = HandlerAudit.unguarded("A.java", source);

        assertEquals(1, findings.size());
        assertEquals("second", findings.get(0).handler());
    }

    @Test
    @DisplayName("a brace inside a string or comment does not end the method early")
    void ignoresBracesThatAreNotCode() {
        String source = """
            class A {
                @EventHandler
                private void onReceive(ReceiveMessageEvent event) {
                    String open = "{";
                    work(open);
                }
            }
            """;

        List<HandlerAudit.Finding> findings = HandlerAudit.unguarded("A.java", source);

        assertEquals(1, findings.size(), "the handler is still found");
        assertFalse(findings.get(0).handler().isEmpty(), "and its name is read");
    }

    @Test
    @DisplayName("a null or empty source is not a finding")
    void handlesEmptyInput() {
        assertTrue(HandlerAudit.unguarded("A.java", null).isEmpty());
        assertTrue(HandlerAudit.unguarded("A.java", "").isEmpty());
    }
}
