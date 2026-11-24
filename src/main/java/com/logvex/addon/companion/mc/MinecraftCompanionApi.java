package com.logvex.addon.companion.mc;

import com.logvex.addon.LogClient;
import com.logvex.addon.bot.BotModule;
import com.logvex.addon.bot.core.BotEvent;
import com.logvex.addon.bot.core.BotState;
import com.logvex.addon.bot.core.BotTask;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.LogSegmentMeta;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerTracker;
import com.logvex.addon.chatlog.core.StatsSnapshot;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.chatlog.mc.LogStorageService;
import com.logvex.addon.chatlog.modules.ChatLoggerModule;
import com.logvex.addon.companion.core.CompanionApi;
import com.logvex.addon.companion.core.CompanionDispatch;
import com.logvex.addon.companion.core.CompanionSnapshot;
import com.logvex.addon.framework.LogClientContext;
import meteordevelopment.meteorclient.systems.modules.Module;
import com.logvex.addon.framework.utils.modules.ModuleLookup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import com.logvex.addon.notify.NotificationModule;
import com.logvex.addon.notify.core.Notification;
import com.logvex.addon.notify.core.NotificationType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Minecraft implementation of the companion API.
 *
 * <p>Every method here runs on the client thread (the router marshals it there), so it can read the
 * game and the addon's services directly. It only reads what the client can actually see: a player is
 * reported from the addon's own tracker, never invented, and the server address comes from the
 * client's current connection, which is null in singleplayer.
 *
 * <p>Actions go through the same public methods the GUI and the commands use, so the app can never
 * make the addon do something a user could not do by hand.
 */
public final class MinecraftCompanionApi implements CompanionApi {
    /** How many recent chat lines and bot events a snapshot carries. */
    private static final int CHAT_LIMIT = 60;
    private static final int EVENT_LIMIT = 25;
    private static final int PLAYER_LIMIT = 200;

    private final String nodeId;
    private final long startedAt = System.currentTimeMillis();

    public MinecraftCompanionApi(String nodeId) {
        this.nodeId = nodeId;
    }

    // ------------------------------------------------------------------ snapshot

    @Override
    public CompanionSnapshot snapshot() {
        MinecraftClient mc = MinecraftClient.getInstance();
        LogService log = LogService.get();
        BotModule bot = Modules.get().get(BotModule.class);

        return new CompanionSnapshot(
            nodeInfo(mc),
            modules(),
            tasks(bot),
            players(log),
            chat(log),
            stats(log),
            storage(log, Modules.get().get(ChatLoggerModule.class)),
            bot(bot),
            capabilities());
    }

    private CompanionSnapshot.NodeInfo nodeInfo(MinecraftClient mc) {
        String playerName = "";
        String serverAddress = "";
        boolean inWorld = false;
        boolean singleplayer = false;
        String gameMode = "unknown";

        if (mc != null) {
            inWorld = mc.world != null && mc.player != null;
            ClientPlayerEntity player = mc.player;
            if (player != null) playerName = player.getGameProfile().getName();
            singleplayer = mc.isInSingleplayer();
            if (mc.getCurrentServerEntry() != null) {
                serverAddress = mc.getCurrentServerEntry().address;
            } else if (singleplayer) {
                serverAddress = "singleplayer";
            }
            if (player != null && player.isCreative()) gameMode = "creative";
            else if (player != null && player.isSpectator()) gameMode = "spectator";
            else if (inWorld) gameMode = "survival";
        }

        return new CompanionSnapshot.NodeInfo(
            nodeId,
            nodeName(),
            com.logvex.addon.framework.LogClientContext.VERSION,
            minecraftVersion(),
            playerName,
            serverAddress,
            inWorld,
            singleplayer,
            gameMode,
            System.currentTimeMillis() - startedAt,
            System.currentTimeMillis());
    }

    private String nodeName() {
        String server = LogService.get().serverName();
        return server == null || server.isBlank() ? "Log Client" : server;
    }

    private static String minecraftVersion() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc == null ? "unknown" : mc.getGameVersion();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private List<CompanionSnapshot.ModuleView> modules() {
        List<CompanionSnapshot.ModuleView> out = new ArrayList<>();
        for (Module module : Modules.get().getAll()) {
            out.add(new CompanionSnapshot.ModuleView(
                module.name,
                module.title,
                module.category == null ? "" : module.category.name,
                module.description == null ? "" : module.description,
                module.isActive()));
        }
        return out;
    }

    private List<CompanionSnapshot.TaskView> tasks(BotModule bot) {
        List<CompanionSnapshot.TaskView> out = new ArrayList<>();
        if (bot == null) return out;
        for (BotTask task : bot.controller().queue().tasks()) {
            out.add(new CompanionSnapshot.TaskView(
                String.valueOf(task.id()),
                task.type().name().toLowerCase(java.util.Locale.ROOT),
                task.label(),
                task.state().name(),
                task.priority().ordinal(),
                task.createdAt(),
                task.startedAt(),
                task.failureReason()));
        }
        return out;
    }

    /**
     * Every player the client can name, online first.
     *
     * <p>Reading only the activity tracker was the bug: it is fed by the chat log, so with logging
     * off the Players tab was empty, and a player who is online but silent never appeared at all.
     * The tab list is the authoritative "who is here", so it is merged in and marked online. The
     * tracker then fills in the history for the names it has seen.
     *
     * <p>Online rows come first, each group ordered by name, so the app can show "online" and
     * "seen before" as two blocks without sorting again. A player known only from the tab list
     * carries zeros for the counters the client has no evidence for.
     */
    private List<CompanionSnapshot.PlayerView> players(LogService log) {
        Map<String, CompanionSnapshot.PlayerView> byName = new LinkedHashMap<>();
        for (TabListPlayer live : tabListPlayers()) {
            byName.put(live.key(), new CompanionSnapshot.PlayerView(
                live.name(), live.uuid(), 0L, 0L, 0, 0L, true, false,
                live.ping(), live.gameMode()));
        }

        PlayerTracker tracker = log == null ? null : log.players();
        if (tracker != null) {
            for (PlayerActivity activity : tracker.search("")) {
                String key = activity.name().toLowerCase(java.util.Locale.ROOT);
                boolean excluded = tracker.isExcluded(activity.name());
                CompanionSnapshot.PlayerView live = byName.get(key);
                if (live != null) {
                    // The tab list has the identity and the live figures; the tracker has the
                    // history. Merging keeps both instead of letting the later source win.
                    byName.put(key, new CompanionSnapshot.PlayerView(
                        live.name(), live.uuid(), activity.firstSeen(), activity.lastSeen(),
                        (int) activity.messageCount(), onlineMs(activity), true, excluded,
                        live.ping(), live.gameMode()));
                    continue;
                }
                byName.put(key, new CompanionSnapshot.PlayerView(
                    activity.name(), "",
                    activity.firstSeen(), activity.lastSeen(),
                    (int) activity.messageCount(), onlineMs(activity),
                    false, excluded, -1, ""));
            }
        }

        List<CompanionSnapshot.PlayerView> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> {
            if (a.online() != b.online()) return a.online() ? -1 : 1;
            return String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
        });
        return out.size() > PLAYER_LIMIT ? List.copyOf(out.subList(0, PLAYER_LIMIT)) : List.copyOf(out);
    }

    /** How long the tracker watched this player, or 0 when it has no span to report. */
    private static long onlineMs(PlayerActivity activity) {
        if (activity.sessionCount() <= 0 || activity.firstSeen() <= 0) return 0L;
        return Math.max(0L, activity.lastSeen() - activity.firstSeen());
    }

    /** One row of the server's tab list. */
    private record TabListPlayer(String name, String uuid, int ping, String gameMode) {
        String key() {
            return name.toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The players the server currently lists, or an empty list when there is no connection.
     *
     * <p>The tab list is the only client-side source of "who is online" that does not depend on
     * anything this addon records, which is why the Players tab is built on it.
     */
    private static List<TabListPlayer> tabListPlayers() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) return List.of();
        List<TabListPlayer> out = new ArrayList<>();
        for (net.minecraft.client.network.PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry == null || entry.getProfile() == null) continue;
            String name = entry.getProfile().getName();
            if (name == null || name.isBlank()) continue;
            String uuid = entry.getProfile().getId() == null
                ? "" : entry.getProfile().getId().toString();
            out.add(new TabListPlayer(name, uuid, entry.getLatency(), gameMode(entry)));
        }
        return out;
    }

    /** The player's game mode in lower case, or "" when the server does not report one. */
    private static String gameMode(net.minecraft.client.network.PlayerListEntry entry) {
        if (entry.getGameMode() == null) return "";
        return entry.getGameMode().getName();
    }

    private List<CompanionSnapshot.ChatEntryView> chat(LogService log) {
        List<CompanionSnapshot.ChatEntryView> out = new ArrayList<>();
        for (ChatEntry entry : log.recent(CHAT_LIMIT)) {
            out.add(new CompanionSnapshot.ChatEntryView(
                entry.timestamp(),
                entry.type().id(),
                entry.sender(),
                entry.content()));
        }
        return out;
    }

    private CompanionSnapshot.StatsView stats(LogService log) {
        StatsSnapshot snapshot = log.snapshot();
        return new CompanionSnapshot.StatsView(
            snapshot.totalMessages(),
            snapshot.uniquePlayers(),
            snapshot.joinEvents(),
            snapshot.leaveEvents(),
            snapshot.deathEvents(),
            snapshot.startedAt());
    }

    private CompanionSnapshot.StorageView storage(LogService log, ChatLoggerModule logger) {
        // The retention cap is what makes a size meaningful: 12 MB is fine against a 200 MB cap and
        // a problem against 16 MB. It comes from the logger's own setting, so the app shows the
        // limit that is actually enforced rather than a number of its own.
        long capBytes = logger == null ? 0L : logger.maxBytes();
        boolean autoCleanup = logger != null && logger.autoCleanupEnabled();
        LogStorageService storage = log.storage();
        if (storage == null || storage.store() == null) {
            return new CompanionSnapshot.StorageView(0, 0, 0, 0, 0, capBytes, autoCleanup);
        }
        List<LogSegmentMeta> segments = storage.segments();
        return new CompanionSnapshot.StorageView(
            segments.size(),
            storage.store().totalCompressedBytes(),
            log.sessionHistory() == null ? 0 : log.sessionHistory().size(),
            storage.store().oldestTimestamp(),
            storage.store().newestTimestamp(),
            capBytes,
            autoCleanup);
    }

    private CompanionSnapshot.BotView bot(BotModule bot) {
        if (bot == null) {
            return new CompanionSnapshot.BotView("Unavailable", "", 0, 0, 0, false, List.of());
        }
        BotState state = bot.controller().state();
        BotTask active = bot.controller().activeTask();
        List<String> events = new ArrayList<>();
        for (BotEvent event : bot.controller().events()) {
            events.add(event.message());
            if (events.size() >= EVENT_LIMIT) break;
        }
        return new CompanionSnapshot.BotView(
            state.toString(),
            active == null ? "" : active.label(),
            bot.controller().queue().size(),
            bot.controller().history().successCount(),
            bot.controller().history().failureCount(),
            state == BotState.PAUSED,
            events);
    }

    private List<String> capabilities() {
        List<String> out = new ArrayList<>();
        out.add("snapshot");
        out.add("bot-control");
        out.add("task-queue");
        out.add("chat");
        out.add("command");
        out.add("modules");
        out.add("events");
        out.add("storage");
        return out;
    }

    // ------------------------------------------------------------------ actions

    @Override
    public ActionResult startBot() {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return ActionResult.fail("The bot is not available.");
        if (!bot.isActive()) bot.toggle();
        return bot.startBot()
            ? ActionResult.ok("The bot is starting.")
            : ActionResult.fail("The bot could not start. Check that you are in a world.");
    }

    @Override
    public ActionResult pauseBot() {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return ActionResult.fail("The bot is not available.");
        return bot.pauseBot() ? ActionResult.ok("The bot is paused.") : ActionResult.fail("The bot is not running.");
    }

    @Override
    public ActionResult resumeBot() {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return ActionResult.fail("The bot is not available.");
        return bot.resumeBot() ? ActionResult.ok("The bot resumed.") : ActionResult.fail("The bot is not paused.");
    }

    @Override
    public ActionResult stopBot() {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return ActionResult.fail("The bot is not available.");
        return bot.stopBot() ? ActionResult.ok("The bot stopped.") : ActionResult.fail("The bot was not running.");
    }

    @Override
    public ActionResult emergencyStopBot() {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return ActionResult.fail("The bot is not available.");
        bot.emergencyStop();
        return ActionResult.ok("Emergency stop engaged.");
    }

    @Override
    public ActionResult enqueueTask(String objective) {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return ActionResult.fail("The bot is not available.");
        String text = objective == null ? "" : objective.trim();
        if (text.isEmpty()) return ActionResult.fail("No objective was given.");
        String[] words = text.split("\\s+");
        String type = "";
        String itemId = "";
        int amount = 1;
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (word.chars().allMatch(Character::isDigit)) {
                try {
                    amount = Math.max(1, Math.min(1_000_000, Integer.parseInt(word)));
                } catch (NumberFormatException ignored) {
                    // An implausible number is left at one rather than failing the whole objective.
                }
                continue;
            }
            if (type.isEmpty() && com.logvex.addon.bot.core.TaskPlanner.typeFor(word) != null) {
                type = word.toLowerCase(java.util.Locale.ROOT);
                continue;
            }
            if (itemId.isEmpty()) {
                String value = word.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
                itemId = value.indexOf(':') < 0 ? "minecraft:" + value : value;
            }
        }
        if (type.isEmpty() && !itemId.isEmpty()) type = "gather";
        if (itemId.isEmpty()) {
            return ActionResult.fail("Could not read an item from that objective.");
        }
        bot.addGoal(text, type, itemId, amount);
        return ActionResult.ok("Queued: " + text + ".");
    }

    @Override
    public ActionResult cancelTask(String taskId) {
        BotModule bot = Modules.get().get(BotModule.class);
        if (bot == null) return ActionResult.fail("The bot is not available.");
        long id;
        try {
            id = Long.parseLong(taskId);
        } catch (NumberFormatException e) {
            return ActionResult.fail("That task id is not valid.");
        }
        return bot.controller().queue().remove(id)
            ? ActionResult.ok("Task cancelled.")
            : ActionResult.fail("No such task is queued.");
    }

    @Override
    public ActionResult setModule(String name, boolean active) {
        Module module = ModuleLookup.find(name);
        if (module == null) return ActionResult.fail("No module is called \"" + name + "\".");
        if (module.isActive() == active) {
            return ActionResult.ok(module.title + " is already " + (active ? "on" : "off") + ".");
        }
        module.toggle();
        return ActionResult.ok(module.title + " turned " + (active ? "on" : "off") + ".");
    }

    @Override
    public ActionResult sendChat(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) return ActionResult.fail("The message was empty.");
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.getNetworkHandler() == null) {
            return ActionResult.fail("You are not in a world.");
        }
        if (text.startsWith("/")) return sendCommand(text);
        // Meteor's own hook on sendChatMessage posts SendMessageEvent, so the line must go in through
        // this method and not through a manual post: posting here as well would offer every line to
        // the listeners twice, and a macro would then run twice.
        mc.getNetworkHandler().sendChatMessage(text);
        return ActionResult.ok("Sent.");
    }

    @Override
    public ActionResult sendCommand(String command) {
        String text = command == null ? "" : command.trim();
        if (text.isEmpty()) return ActionResult.fail("The command was empty.");
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getNetworkHandler() == null) {
            return ActionResult.fail("You are not in a world.");
        }
        // Nothing hooks sendChatCommand, so the event is posted here to give a macro or an addon
        // command the same chance it gets when the player types the line. A listener that claims it
        // has already sent its own command.
        CompanionDispatch.Outcome outcome = CompanionDispatch.command(LogClientContext.EVENT_BUS, text);
        if (outcome.handled()) return ActionResult.ok("Handled by a macro or command.");
        String out = outcome.text();
        if (out.startsWith("/")) out = out.substring(1);
        mc.getNetworkHandler().sendChatCommand(out);
        return ActionResult.ok("Command sent.");
    }

    @Override
    public ActionResult notify(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) return ActionResult.fail("The message was empty.");
        ChatUtils.info(text);
        NotificationModule notifications = Modules.get().get(NotificationModule.class);
        if (notifications != null) {
            notifications.center().publish(Notification.of(
                NotificationType.GENERAL, "companion", "Companion", text));
        }
        return ActionResult.ok("Shown.");
    }

    @Override
    public Map<String, Object> query(String name, Map<String, String> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        LogService log = LogService.get();
        String key = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        switch (key) {
            case "messages", "chat" -> {
                int limit = intParam(params, "limit", 50);
                String needle = params.getOrDefault("q", "").toLowerCase(java.util.Locale.ROOT);
                String player = params.getOrDefault("player", "").toLowerCase(java.util.Locale.ROOT);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ChatEntry entry : log.recent(Math.min(200, Math.max(1, limit * 2)))) {
                    if (!needle.isEmpty() && !entry.matches(needle)) continue;
                    if (!player.isEmpty() && !entry.sender().toLowerCase(java.util.Locale.ROOT).equals(player)) {
                        continue;
                    }
                    rows.add(entry.toMap());
                    if (rows.size() >= limit) break;
                }
                body.put("entries", rows);
                body.put("count", rows.size());
            }
            case "players" -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (PlayerActivity activity : log.players().search(params.getOrDefault("q", ""))) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", activity.name());
                    row.put("messages", activity.messageCount());
                    row.put("firstSeen", activity.firstSeen());
                    row.put("lastSeen", activity.lastSeen());
                    rows.add(row);
                }
                body.put("players", rows);
                body.put("count", rows.size());
            }
            case "stats" -> {
                StatsSnapshot snapshot = log.snapshot();
                body.put("messages", snapshot.totalMessages());
                body.put("players", snapshot.uniquePlayers());
                body.put("joins", snapshot.joinEvents());
                body.put("leaves", snapshot.leaveEvents());
                body.put("deaths", snapshot.deathEvents());
            }
            default -> {
                return Map.of("ok", false, "message", "Unknown query: " + name);
            }
        }
        body.put("ok", true);
        return body;
    }

    @Override
    public List<String> queries() {
        return List.of("messages", "players", "stats");
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        String value = params.get(key);
        if (value == null) return fallback;
        try {
            return Math.max(1, Math.min(500, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The addon's version string, exposed so the app can show it without another call. */
    public String addonVersion() {
        return LogClientContext.VERSION;
    }

    /** Kept for symmetry with the LogClient bootstrap; unused but documents the dependency. */
    static Class<?> bootstrap() {
        return LogClient.class;
    }

    /** True when the client is currently in a world, used by the module to decide when to start. */
    public static boolean inWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.world != null && mc.player != null;
    }

    /** The current world, or null. Present so a future feature can read it without re-fetching. */
    static ClientWorld world() {
        return MinecraftClient.getInstance() == null ? null : MinecraftClient.getInstance().world;
    }
}
