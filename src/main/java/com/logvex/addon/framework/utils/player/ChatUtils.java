package com.logvex.addon.framework.utils.player;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Sends addon messages to the client chat.
 *
 * <p>All formatting goes through {@link #format}, which never lets a stray {@code %} in a message
 * body turn into a {@link java.util.MissingFormatArgumentException}: a message containing no format
 * specifier is printed verbatim rather than being passed to {@code String.format}. Messages are
 * never allowed to carry a stack trace.
 */
public final class ChatUtils {
    private static final String PREFIX = "[Log Addon] ";
    private static String forcedPrefix;

    private ChatUtils() {
    }

    public static void forceNextPrefixClass(Class<?> klass) {
        forcedPrefix = null;
    }

    public static Text getAddonPrefix() {
        return Text.literal(PREFIX).formatted(Formatting.AQUA);
    }

    /** Formats {@code message} with {@code args} only when it actually contains a specifier. */
    static String format(String message, Object... args) {
        if (message == null) return "";
        if (args == null || args.length == 0 || message.indexOf('%') < 0) return message;
        try {
            return String.format(message, args);
        } catch (RuntimeException e) {
            return message;
        }
    }

    public static void info(String message, Object... args) {
        send(Formatting.WHITE, message, args);
    }

    public static void infoPrefix(String prefix, String message, Object... args) {
        send(Formatting.WHITE, prefix == null ? message : prefix + " " + message, args);
    }

    public static void warning(String message, Object... args) {
        send(Formatting.YELLOW, message, args);
    }

    public static void warningPrefix(String prefix, String message, Object... args) {
        send(Formatting.YELLOW, prefix == null ? message : prefix + " " + message, args);
    }

    public static void error(String message, Object... args) {
        send(Formatting.RED, message, args);
    }

    public static void errorPrefix(String prefix, String message, Object... args) {
        send(Formatting.RED, prefix == null ? message : prefix + " " + message, args);
    }

    public static void sendPlayerMsg(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || message == null) return;
        mc.player.networkHandler.sendChatMessage(message);
    }

    public static void sendMsg(Text message) {
        if (message == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(message);
    }

    public static void sendMsg(String prefix, Text message) {
        sendMsg(message);
    }

    public static void sendMsg(Formatting color, String message, Object... args) {
        send(color, message, args);
    }

    private static void send(Formatting color, String message, Object... args) {
        MutableText text = Text.literal(PREFIX).formatted(Formatting.AQUA)
            .append(Text.literal(format(message, args)).formatted(color));
        sendMsg(text);
    }
}
