package com.logvex.addon.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds {@code @EventHandler} methods whose body is not run through {@link SafeEventHandler}.
 *
 * <p>Meteor's event bus is plain orbit, and orbit's {@code post} loops over the listeners with no
 * try/catch. A handler that throws therefore unwinds out of whatever vanilla method posted the event
 * — {@code MinecraftClient.disconnect()}, the tick loop, the join path — and takes the client down.
 * The guard is the only thing standing between one bad tick and a crashed game, so it has to be on
 * every handler, including the ones added later.
 *
 * <p>The check reads source rather than bytecode because it has to run in a plain unit test with no
 * game classes loaded. It is deliberately simple: it locates each {@code @EventHandler} and looks for
 * a guard call before the method's closing brace. That is enough to catch the mistake it exists for —
 * a new handler written without the wrapper — without pretending to be a Java parser.
 */
public final class HandlerAudit {

    /** One unguarded handler. */
    public record Finding(String file, int line, String handler) {
        @Override
        public String toString() {
            return file + ":" + line + " " + handler;
        }
    }

    private HandlerAudit() {
    }

    /**
     * Reports every {@code @EventHandler} in {@code source} whose body does not mention
     * {@code SafeEventHandler}.
     *
     * @param file the name to report the finding against
     * @param source the file's text
     */
    public static List<Finding> unguarded(String file, String source) {
        List<Finding> findings = new ArrayList<>();
        if (source == null) return findings;
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("@EventHandler")) continue;
            int open = i + 1;
            while (open < lines.length && !lines[open].contains("{")) open++;
            if (open >= lines.length) continue;
            String handler = handlerName(lines, i, open);
            int depth = 0;
            boolean guarded = false;
            for (int close = open; close < lines.length; close++) {
                String line = lines[close];
                if (line.contains("SafeEventHandler")) guarded = true;
                depth += count(line, '{') - count(line, '}');
                if (depth <= 0 && close > open) break;
            }
            if (!guarded) findings.add(new Finding(file, i + 1, handler));
        }
        return findings;
    }

    /** The method name between the annotation and its opening brace, or "" when it cannot be read. */
    private static String handlerName(String[] lines, int annotation, int open) {
        StringBuilder signature = new StringBuilder();
        for (int i = annotation + 1; i <= open && i < lines.length; i++) {
            signature.append(lines[i]).append(' ');
        }
        String text = signature.toString();
        int paren = text.indexOf('(');
        if (paren < 0) return "";
        int end = paren;
        while (end > 0 && Character.isJavaIdentifierPart(text.charAt(end - 1))) end--;
        return text.substring(end, paren);
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) n++;
        }
        return n;
    }
}
