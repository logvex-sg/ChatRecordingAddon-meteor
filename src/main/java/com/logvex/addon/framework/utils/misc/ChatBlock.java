package com.logvex.addon.framework.utils.misc;

import com.logvex.addon.chatlog.core.ChatEntry;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.chatlog.mc.LogService;
import com.logvex.addon.framework.LogClientContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Prints a multi-line block to the local chat box as one message.
 *
 * <p>Meteor's {@code Command.info} routes through {@code ChatUtils}, which prepends
 * {@code [Meteor][Log Client] } to every call. Printing a card line by line therefore puts a prefix
 * in front of each row, and the columns stop lining up — the card looks broken even though the text
 * is right. A single {@code addMessage} with embedded newlines gives the whole card one prefix.
 *
 * <p>Printing straight to the chat box bypasses {@code ReceiveMessageEvent}, which is the only place
 * {@code ChatRecorder} sees a line. Because this addon exists to record chat, the block is recorded
 * explicitly; otherwise its own answers would be on screen but missing from the log and export.
 */
public final class ChatBlock {
    private ChatBlock() {
    }

    /** Prints {@code lines} as one unprefixed message and records it in the chat archive. */
    public static void print(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        String text = String.join("\n", lines);
        if (text.isBlank()) return;
        MinecraftClient mc = LogClientContext.mc;
        if (mc == null || mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal(text));
        record(text, mc);
    }

    /**
     * Adds {@code text} to the chat archive.
     *
     * <p>The archive is a convenience, so a failure to record must never swallow the answer that is
     * already on screen; a recording error is therefore contained here.
     */
    public static void record(String text, MinecraftClient mc) {
        if (text == null || text.isBlank()) return;
        String sender = mc != null && mc.player != null && mc.player.getGameProfile() != null
            ? String.valueOf(mc.player.getGameProfile().getName()) : "";
        try {
            LogService service = LogService.get();
            service.record(new ChatEntry(System.currentTimeMillis(), MessageType.SYSTEM, sender,
                text, service.serverName(), ""));
        } catch (RuntimeException ignored) {
            // See above: the answer is already visible, so this must not become the failure.
        }
    }
}
