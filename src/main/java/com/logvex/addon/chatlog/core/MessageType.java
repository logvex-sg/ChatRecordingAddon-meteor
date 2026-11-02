package com.logvex.addon.chatlog.core;

import java.util.Locale;

/**
 * Category of a recorded chat line. Classification happens once, when the message arrives, and is
 * stored with the entry so filtering and statistics never have to re-parse historical text.
 */
public enum MessageType {
    PUBLIC_CHAT,
    PLAYER_MESSAGE,
    SYSTEM,
    JOIN,
    LEAVE,
    DEATH,
    ANNOUNCEMENT,
    GAME,
    OTHER;

    public static MessageType fromString(String value) {
        if (value == null) return OTHER;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "public_chat", "publicchat", "chat" -> PUBLIC_CHAT;
            case "player_message", "playermessage", "private" -> PLAYER_MESSAGE;
            case "system" -> SYSTEM;
            case "join" -> JOIN;
            case "leave" -> LEAVE;
            case "death" -> DEATH;
            case "announcement" -> ANNOUNCEMENT;
            case "game" -> GAME;
            default -> OTHER;
        };
    }

    /** Stable lowercase id used in stored entries and the config. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return switch (this) {
            case PUBLIC_CHAT -> "Public Chat";
            case PLAYER_MESSAGE -> "Player Message";
            case SYSTEM -> "System";
            case JOIN -> "Join";
            case LEAVE -> "Leave";
            case DEATH -> "Death";
            case ANNOUNCEMENT -> "Announcement";
            case GAME -> "Game";
            case OTHER -> "Other";
        };
    }
}
