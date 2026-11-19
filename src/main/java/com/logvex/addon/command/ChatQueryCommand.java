package com.logvex.addon.command;

import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.PlayerActivity;
import com.logvex.addon.chatlog.core.PlayerSession;
import com.logvex.addon.chatlog.core.ReportFormatting;
import com.logvex.addon.chatlog.mc.LogService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code !chat} — quick questions about who is around and who has been talking.
 *
 * <p>Everything here answers from what the client has actually observed: joins and leaves it saw,
 * chat lines it recorded. A player who is online but silent still appears under {@code online}
 * because their join was observed, but the mod never claims to know who is online now beyond that.
 *
 * <p>Read-only by design. Nothing in this command changes state, so it is safe to run at any time,
 * including while the recorder is off — it simply has less to report.
 */
public class ChatQueryCommand extends Command {
    private static final int MAX_ROWS = 10;

    public ChatQueryCommand() {
        super("chat", "Ask who is online, who was last seen, and who talks most.", "who", "cq");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            overview();
            return SINGLE_SUCCESS;
        });

        builder.then(literal("online").executes(context -> {
            online();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("seen")
            .executes(context -> {
                warning("Name a player: %sseen <player>.", prefix());
                return SINGLE_SUCCESS;
            })
            .then(argument("player", StringArgumentType.greedyString()).executes(context -> {
                seen(StringArgumentType.getString(context, "player"));
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("top").executes(context -> {
            topMessages();
            return SINGLE_SUCCESS;
        }).then(literal("time").executes(context -> {
            topTime();
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("recent").executes(context -> {
            recent(MAX_ROWS);
            return SINGLE_SUCCESS;
        }).then(argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50))
            .executes(context -> {
                recent(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "count"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("me").executes(context -> {
            me();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("help").executes(context -> {
            help();
            return SINGLE_SUCCESS;
        }));
    }

    private static String prefix() {
        return com.logvex.addon.framework.systems.config.ClientConfig.get().prefix();
    }

    private void overview() {
        LogService log = LogService.get();
        List<ReplyFormat.Field> fields = new ArrayList<>();
        fields.add(new ReplyFormat.Field("Online now", String.valueOf(log.sessions().onlineCount())));
        fields.add(new ReplyFormat.Field("Players seen", String.valueOf(log.players().size())));
        fields.add(new ReplyFormat.Field("Recorded lines", String.valueOf(log.liveCount())));
        fields.add(new ReplyFormat.Field("Recording", log.isRecording() ? "on" : "off"));
        CommandOutput.send(this, "Chat overview", fields,
            prefix() + "chat online | seen <player> | top | recent");
    }

    private void online() {
        LogService log = LogService.get();
        List<PlayerSession> sessions = log.sessions().search("");
        List<PlayerSession> online = new ArrayList<>();
        for (PlayerSession session : sessions) if (session.online()) online.add(session);

        if (online.isEmpty()) {
            info("No players are marked online. The client only knows about joins and leaves it saw.");
            return;
        }
        long now = System.currentTimeMillis();
        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (int i = 0; i < online.size() && i < MAX_ROWS; i++) {
            PlayerSession session = online.get(i);
            // An online player has a known start time even with no completed session yet, so the
            // duration is always available here; hasMeasuredTime() would wrongly say "unknown".
            fields.add(new ReplyFormat.Field(session.name(),
                "online " + ReportFormatting.duration(session.onlineMs(now))));
        }
        CommandOutput.send(this, "Online (" + online.size() + ")", fields,
            online.size() > MAX_ROWS ? "Showing " + MAX_ROWS + " of " + online.size() : "");
    }

    private void seen(String name) {
        String target = name == null ? "" : name.strip();
        if (target.isEmpty()) {
            warning("Name a player: %sseen <player>.", prefix());
            return;
        }
        LogService log = LogService.get();
        PlayerActivity activity = log.players().get(target);
        PlayerSession session = log.sessions().get(target);

        if (activity == null && session == null) {
            info("The client has never seen '%s' join, leave or speak.", target);
            return;
        }
        long now = System.currentTimeMillis();
        List<ReplyFormat.Field> fields = new ArrayList<>();
        if (session != null) {
            fields.add(new ReplyFormat.Field("Status", session.online() ? "online" : "last seen "
                + ReportFormatting.duration(Math.max(0, now - session.lastSeen())) + " ago"));
            fields.add(new ReplyFormat.Field("Online time",
                hasTime(session) ? ReportFormatting.duration(session.onlineMs(now)) : "unknown"));
            fields.add(new ReplyFormat.Field("Sessions", String.valueOf(session.sessions())));
        }
        if (activity != null) {
            fields.add(new ReplyFormat.Field("Messages", String.valueOf(activity.messageCount())));
            fields.add(new ReplyFormat.Field("Joins", String.valueOf(activity.joinCount())));
            fields.add(new ReplyFormat.Field("Leaves", String.valueOf(activity.leaveCount())));
            fields.add(new ReplyFormat.Field("Deaths", String.valueOf(activity.deathCount())));
            fields.add(new ReplyFormat.Field("First seen", ReportFormatting.time(activity.firstSeen())));
        }
        CommandOutput.send(this, "Player " + target, fields, "");
    }

    private void topMessages() {
        List<PlayerActivity> all = LogService.get().players().search("");
        List<PlayerActivity> ranked = new ArrayList<>();
        for (PlayerActivity activity : all) if (activity.messageCount() > 0) ranked.add(activity);
        ranked.sort((a, b) -> Long.compare(b.messageCount(), a.messageCount()));

        if (ranked.isEmpty()) {
            info("No player messages have been recorded yet.");
            return;
        }
        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (int i = 0; i < ranked.size() && i < MAX_ROWS; i++) {
            PlayerActivity activity = ranked.get(i);
            fields.add(new ReplyFormat.Field((i + 1) + ". " + activity.name(),
                activity.messageCount() + (activity.messageCount() == 1 ? " message" : " messages")));
        }
        CommandOutput.send(this, "Most messages", fields, prefix() + "chat top time for online time");
    }

    private void topTime() {
        long now = System.currentTimeMillis();
        List<PlayerSession> ranked = LogService.get().sessions().byOnlineTime(now);
        List<PlayerSession> measured = new ArrayList<>();
        for (PlayerSession session : ranked) if (hasTime(session)) measured.add(session);

        if (measured.isEmpty()) {
            info("No measured session times yet; online time needs an observed join and leave.");
            return;
        }
        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (int i = 0; i < measured.size() && i < MAX_ROWS; i++) {
            PlayerSession session = measured.get(i);
            fields.add(new ReplyFormat.Field((i + 1) + ". " + session.name(),
                ReportFormatting.duration(session.onlineMs(now)) + (session.online() ? " (online)" : "")));
        }
        CommandOutput.send(this, "Longest online", fields, "");
    }

    private void recent(int count) {
        LogService log = LogService.get();
        List<ChatEntry> entries = log.recent(Math.max(count * 4, count));
        List<ReplyFormat.Field> fields = new ArrayList<>();
        for (ChatEntry entry : entries) {
            if (fields.size() >= count) break;
            if (entry.sender().isBlank()) continue;
            if (entry.type() != com.logvex.addon.chatlog.core.MessageType.PUBLIC_CHAT
                && entry.type() != com.logvex.addon.chatlog.core.MessageType.PLAYER_MESSAGE) continue;
            fields.add(new ReplyFormat.Field(entry.sender(), shorten(entry.content())));
        }
        if (fields.isEmpty()) {
            info("No recent player chat in the live buffer.");
            return;
        }
        CommandOutput.send(this, "Recent chat", fields, "");
    }

    private void me() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) {
            warning("Join a world first.");
            return;
        }
        seen(mc.player.getGameProfile().getName());
    }

    /**
     * True when an online duration can be shown.
     *
     * <p>{@link PlayerSession#hasMeasuredTime()} only counts completed join/leave sessions, so a
     * player who is online right now reports false. Their current stretch is still known, so the
     * duration is meaningful and "unknown" would be wrong.
     */
    private static boolean hasTime(PlayerSession session) {
        return session != null && (session.hasMeasuredTime() || session.online());
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= 40 ? flat : flat.substring(0, 39) + "\u2026";
    }

    private void help() {
        List<ReplyFormat.Field> fields = new ArrayList<>();
        fields.add(new ReplyFormat.Field(prefix() + "chat online", "Players whose join the client saw."));
        fields.add(new ReplyFormat.Field(prefix() + "chat seen <player>", "When they were last seen, and their activity."));
        fields.add(new ReplyFormat.Field(prefix() + "chat top", "Who has sent the most messages."));
        fields.add(new ReplyFormat.Field(prefix() + "chat top time", "Who has been online the longest."));
        fields.add(new ReplyFormat.Field(prefix() + "chat recent [n]", "The last n recorded player lines."));
        fields.add(new ReplyFormat.Field(prefix() + "chat me", "Your own observed activity."));
        CommandOutput.send(this, "Chat queries", fields, prefix() + "prefix copies every command");
    }
}
