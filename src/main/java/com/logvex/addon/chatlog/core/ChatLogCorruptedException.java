package com.logvex.addon.chatlog.core;

/** Thrown when a stored chat log segment fails validation. */
public class ChatLogCorruptedException extends Exception {
    public ChatLogCorruptedException(String message) {
        super(message);
    }

    public ChatLogCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
