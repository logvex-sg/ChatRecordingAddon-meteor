package com.logvex.addon.framework.events.game;

import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.Text;

/**
 * Posted for every chat or system message the client receives.
 *
 * <p>The {@linkplain #getIndicator() indicator} is how the client distinguishes a player message
 * (it carries a signature indicator) from a system message; the recorder relies on that to classify
 * without re-parsing the text.
 */
public class ReceiveMessageEvent {
    private Text message;
    private final MessageIndicator indicator;

    public ReceiveMessageEvent(Text message, MessageIndicator indicator) {
        this.message = message;
        this.indicator = indicator;
    }

    public Text getMessage() {
        return message;
    }

    public void setMessage(Text message) {
        this.message = message;
    }

    public MessageIndicator getIndicator() {
        return indicator;
    }
}
