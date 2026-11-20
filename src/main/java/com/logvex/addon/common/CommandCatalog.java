package com.logvex.addon.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one list of every command this addon provides.
 *
 * <p>It used to be prose: each command printed its own help lines, so the list in chat, the window's
 * About page and the copyable list a user pastes into Discord could disagree with each other and
 * with the dispatcher. Keeping the list here means a command is described once and every surface —
 * chat help, the window, the clipboard — renders the same text from the same source.
 *
 * <p>Minecraft-free on purpose: the wording, the grouping and the layout are all decided here and
 * unit tested, so a change to the help text cannot break at runtime.
 */
public final class CommandCatalog {
    /** A group of related commands, used as one section of the help output. */
    public enum Group {
        CORE("core", "Core"),
        CHAT("chat", "Chat & logging"),
        ALERTS("alerts", "Alerts & webhooks"),
        AUTOMATION("automation", "Automation & bot"),
        BUILD("build", "Schematics & building"),
        WORLD("world", "World"),
        REMOTE("remote", "Companion & updates"),
        ASSISTANT("assistant", "Assistant");

        private final String key;
        private final String title;

        Group(String key, String title) {
            this.key = key;
            this.title = title;
        }

        /** Stable lowercase id used in {@code !log help <key>}. */
        public String key() {
            return key;
        }

        public String title() {
            return title;
        }

        /**
         * Resolves a group from its key or its title, case-insensitively.
         *
         * @return the group, or null when the text names none of them
         */
        public static Group parse(String text) {
            if (text == null) return null;
            String needle = text.strip().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) return null;
            for (Group group : values()) {
                if (group.key.equals(needle) || group.title.toLowerCase(Locale.ROOT).equals(needle)) {
                    return group;
                }
            }
            return null;
        }
    }

    /** One command: the group it belongs to, how it is typed, and what it does. */
    public record Entry(Group group, String usage, String description) {
        public Entry {
            if (group == null) group = Group.CORE;
            if (usage == null) usage = "";
            if (description == null) description = "";
        }
    }

    /**
     * Every command, grouped. The order here is the order the help prints, so the most-used groups
     * come first.
     */
    private static final List<Entry> ENTRIES = List.of(
        // Core -------------------------------------------------------------------------------
        new Entry(Group.CORE, "log help [group]",
            "List every command, or one group in detail."),
        new Entry(Group.CORE, "log status",
            "Overall state: recording, storage, automation and notifications."),
        new Entry(Group.CORE, "log gui",
            "Open the Log Client window."),
        new Entry(Group.CORE, "log modules",
            "List every Log Client module and whether it is on."),
        new Entry(Group.CORE, "log modules check",
            "Report module names claimed twice, which silently deletes a module and crashes lookups."),
        new Entry(Group.CORE, "log stats",
            "This session's message, player and keyword figures."),
        new Entry(Group.CORE, "prefix [command]",
            "Copy every command prefix to the clipboard, or just one."),
        new Entry(Group.CORE, "chat <online|seen|top|recent|me>",
            "Ask about who is online, when a player was last seen, and who talks most."),

        // Chat & logging ---------------------------------------------------------------------
        new Entry(Group.CHAT, "log record <on|off>",
            "Turn chat recording on or off."),
        new Entry(Group.CHAT, "log search [terms]",
            "Search the recorded archive."),
        new Entry(Group.CHAT, "log digest",
            "Summarise recent chat activity."),
        new Entry(Group.CHAT, "log timeline [filter]",
            "Recent session milestones: joins, leaves and deaths."),
        new Entry(Group.CHAT, "log filter [stats|reset]",
            "Chat filter status and counters."),
        new Entry(Group.CHAT, "log notes <subcommand>",
            "Notes you keep about players: list, add, remove, search."),
        new Entry(Group.CHAT, "log identities <subcommand>",
            "Observed name changes: renamed, watch, unwatch, watchlist, clear."),
        new Entry(Group.CHAT, "log sessions [list|clear]",
            "Past recording sessions."),
        new Entry(Group.CHAT, "log playtime [player]",
            "Observed player session durations."),
        new Entry(Group.CHAT, "log importlog <file>",
            "Import a chat log file into the archive."),

        // Alerts & webhooks ------------------------------------------------------------------
        new Entry(Group.ALERTS, "log alerts [count|clear]",
            "Recent keyword, webhook and error alerts."),
        new Entry(Group.ALERTS, "log notifications [count|clear]",
            "Recent notifications."),
        new Entry(Group.ALERTS, "log webhook <subcommand>",
            "Embeds and delivery: status, test, preview, digest, leaderboard, profile, set, accent."),

        // Automation & bot -------------------------------------------------------------------
        new Entry(Group.AUTOMATION, "bot <subcommand>",
            "The bot: status, start, pause, resume, stop, emergency, goto, tasks, waypoint, profile."),
        new Entry(Group.AUTOMATION, "log tasks [list|add|pause|resume|stop|clear]",
            "The bot task queue."),
        new Entry(Group.AUTOMATION, "log waypoints [list|add <name>]",
            "Bot waypoints."),
        new Entry(Group.AUTOMATION, "log import <file>",
            "Import a material list."),
        new Entry(Group.AUTOMATION, "autologin [status|on|off|test|servers]",
            "Auto Login: answers a server's /login prompt with the stored password."),
        new Entry(Group.AUTOMATION, "autologin [gui|clear|clearall]",
            "Where the password goes: set it in a masked field, or clear stored ones."),

        // Schematics & building ---------------------------------------------------------------
        new Entry(Group.BUILD, "log schematic [status|load|start|pause|resume|stop]",
            "Schematic dropper control."),
        new Entry(Group.BUILD, "log acquire [start|pause|resume|stop|status|retry|import]",
            "Creative material acquisition."),

        // World --------------------------------------------------------------------------------
        new Entry(Group.WORLD, "log world [status|map|places|nearest|mark|export]",
            "World intelligence: places, biomes and resources."),

        // Companion & updates --------------------------------------------------------------------
        new Entry(Group.REMOTE, "log companion [status|start|stop|token|regenerate|gui]",
            "The companion web app."),
        new Entry(Group.REMOTE, "log update [status|check|download|channel|gui]",
            "GitHub release updates."),
        new Entry(Group.REMOTE, "log web",
            "Open the local web console in your browser."),

        // Assistant ------------------------------------------------------------------------------
        new Entry(Group.ASSISTANT, "ai [status|triggers|facts|remember|forget|test|gui]",
            "The chat assistant."),
        new Entry(Group.ASSISTANT, "ai placeholders [section]",
            "The reply placeholders, one section at a time."),
        new Entry(Group.ASSISTANT, "ai perms [list|gui|set|allow|deny|admin|reset]",
            "Who may run each command.")
    );

    private CommandCatalog() {
    }

    /** Every entry, in print order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static int count() {
        return ENTRIES.size();
    }

    /** The entries in {@code group}, in print order. */
    public static List<Entry> inGroup(Group group) {
        List<Entry> out = new ArrayList<>();
        if (group == null) return out;
        for (Entry entry : ENTRIES) {
            if (entry.group() == group) out.add(entry);
        }
        return out;
    }

    /** One group and how many commands it holds, for the help overview. */
    public record GroupSummary(Group group, int count) {
    }

    /** One row per group that holds commands, in enum order. */
    public static List<GroupSummary> groupSummary() {
        List<GroupSummary> out = new ArrayList<>();
        for (Group group : Group.values()) {
            int size = inGroup(group).size();
            if (size > 0) out.add(new GroupSummary(group, size));
        }
        return out;
    }

    /** {@code usage} with the addon's prefix in front, e.g. {@code !log status}. */
    public static String prefixed(String prefix, Entry entry) {
        String p = prefix == null ? "" : prefix;
        return p + (entry == null ? "" : entry.usage());
    }

    /**
     * The full command list as prefixed lines, one per command.
     *
     * <p>This is what the copy button puts on the clipboard: something that can be pasted as-is into
     * chat or a Discord message, so it carries no alignment padding or descriptions.
     */
    public static List<String> copyable(String prefix) {
        List<String> out = new ArrayList<>();
        for (Entry entry : ENTRIES) out.add(prefixed(prefix, entry));
        return out;
    }

    /** The same list as one newline-separated block, ready for the clipboard. */
    public static String copyableBlock(String prefix) {
        return String.join("\n", copyable(prefix));
    }

    /**
     * The entries whose command word matches {@code name}.
     *
     * <p>Matching is on the first word of the usage, so {@code "log"} finds every {@code log ...}
     * command and {@code "chat"} finds the {@code chat} command. A leading prefix is ignored so a
     * pasted {@code !log} works as well as {@code log}.
     */
    public static List<Entry> find(String name) {
        List<Entry> out = new ArrayList<>();
        if (name == null) return out;
        String needle = name.strip().toLowerCase(Locale.ROOT);
        while (!needle.isEmpty() && !Character.isLetterOrDigit(needle.charAt(0))) {
            needle = needle.substring(1);
        }
        if (needle.isEmpty()) return out;
        for (Entry entry : ENTRIES) {
            if (CommandLine.firstWord(entry.usage()).toLowerCase(Locale.ROOT).equals(needle)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** One group as prefixed lines with descriptions, for the window. */
    public static List<String> describe(String prefix, Group group) {
        List<String> out = new ArrayList<>();
        for (Entry entry : inGroup(group)) {
            out.add(prefixed(prefix, entry) + "  -  " + entry.description());
        }
        return out;
    }

    /**
     * A sample line exactly as it would be copied: prefix applied, taken from the catalog.
     *
     * <p>Derived rather than hardcoded so the example cannot advertise a command that was renamed.
     */
    public static String example(String prefix) {
        List<Entry> chat = inGroup(Group.CHAT);
        Entry pick = chat.isEmpty() ? ENTRIES.get(0) : chat.get(0);
        return prefixed(prefix, pick);
    }
}
