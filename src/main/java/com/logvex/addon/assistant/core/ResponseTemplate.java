package com.logvex.addon.assistant.core;

import com.logvex.addon.common.Messages;

import java.util.Locale;

/**
 * Expands the placeholders in a reply template.
 *
 * <p>A reply is authored by the user, so the expansion is intentionally small and predictable. Only
 * a fixed set of named placeholders is substituted; anything else is left untouched, so a literal
 * {@code {}} or an unknown name never throws and never silently disappears.
 *
 * <p>Supported placeholders:
 * <ul>
 *   <li>{@code {player}} — the sender of the triggering message</li>
 *   <li>{@code {message}} — the triggering message text</li>
 *   <li>{@code {time}} — the current wall-clock time</li>
 *   <li>{@code {date}} — the current date and time</li>
 *   <li>{@code {server}} — the current server name</li>
 *   <li>{@code {fact:key}} — a stored fact, or empty when unknown</li>
 *   <li>{@code {count:name}} — a stored counter, or {@code 0}</li>
 *   <li>{@code {memory}} — the number of stored facts</li>
 * </ul>
 */
public final class ResponseTemplate {
    private ResponseTemplate() {
    }

    /** The data a template can interpolate. */
    public record Context(String player, String message, String server, AssistantMemory memory, long now) {
        public static Context of(String player, String message, AssistantMemory memory, long now) {
            return new Context(player, message, "", memory, now);
        }
    }

    /** Expands {@code template} against {@code context}. A null template yields an empty string. */
    public static String expand(String template, Context context) {
        if (template == null || template.isEmpty()) return "";
        if (context == null) return template;

        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i + 1);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            String token = template.substring(i + 1, close).trim();
            String replacement = resolve(token, context);
            if (replacement == null) {
                // Unknown placeholder: keep it verbatim so nothing is lost.
                out.append(template, i, close + 1);
            } else {
                out.append(replacement);
            }
            i = close + 1;
        }
        return out.toString();
    }

    private static String resolve(String token, Context context) {
        if (token.isEmpty()) return null;
        String lower = token.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "player":
                return Messages.player(context.player());
            case "message":
                return context.message() == null ? "" : context.message();
            case "time":
                return Messages.clock(context.now());
            case "date":
                return Messages.dateTime(context.now());
            case "server":
                return context.server() == null ? "" : context.server();
            case "memory":
                return context.memory() == null ? "0" : String.valueOf(context.memory().facts().size());
            default:
                break;
        }
        if (lower.startsWith("fact:")) {
            String key = token.substring(token.indexOf(':') + 1).trim();
            if (context.memory() == null) return "";
            return context.memory().fact(key).map(AssistantMemory.Fact::value).orElse("");
        }
        if (lower.startsWith("count:")) {
            String name = token.substring(token.indexOf(':') + 1).trim();
            if (context.memory() == null) return "0";
            return String.valueOf(context.memory().counter(name));
        }
        return null;
    }
}
