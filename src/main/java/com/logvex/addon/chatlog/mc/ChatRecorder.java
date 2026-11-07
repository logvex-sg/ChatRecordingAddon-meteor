package com.logvex.addon.chatlog.mc;

import com.logvex.addon.chatlog.core.ChatClassifier;
import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.framework.events.game.GameJoinedEvent;
import com.logvex.addon.framework.events.game.GameLeftEvent;
import com.logvex.addon.framework.events.game.ReceiveMessageEvent;
import com.logvex.addon.framework.orbit.EventHandler;

/**
 * The single subscriber for incoming chat. Registered once by the addon, independent of which
 * modules are enabled, so recording never depends on a module's event subscription and never
 * double-records.
 *
 * <p>Classification is intentionally cheap: the message text is classified and handed to
 * {@link LogService#record}, which does the (bounded, allocation-light) bookkeeping.
 */
public final class ChatRecorder {
    private final LogService service = LogService.get();

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (event.getMessage() == null) return;

        boolean fromPlayer = event.getIndicator() != null;
        String content = event.getMessage().getString();
        if (content == null || content.isBlank()) return;

        ChatClassifier.Result result = ChatClassifier.classify(fromPlayer, content);
        if (!service.shouldRecord(result.type())) return;

        service.record(new ChatEntry(System.currentTimeMillis(), result.type(), result.sender(),
            content, service.serverName(), ""));
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        service.startSession(serverAddress(), System.currentTimeMillis());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        service.endSession(System.currentTimeMillis());
    }

    private String serverAddress() {
        var mc = com.logvex.addon.framework.LogClientContext.mc;
        if (mc == null) return "unknown";
        if (mc.getCurrentServerEntry() != null) {
            String address = mc.getCurrentServerEntry().address;
            if (address != null && !address.isBlank()) return address;
        }
        return mc.isInSingleplayer() ? "singleplayer" : "unknown";
    }
}
