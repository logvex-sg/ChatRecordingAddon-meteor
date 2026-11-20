package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.ChatTimestampFormat;
import com.logvex.addon.framework.systems.modules.AddonModule;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Puts a clock time in front of every incoming chat line.
 *
 * <p>The log already stamps each message, but the chat HUD shows no time at all, so a line that
 * scrolls past cannot be placed. This rewrites the displayed message to carry a short prefix.
 *
 * <p>The rewrite happens on {@link ReceiveMessageEvent} by replacing the message rather than by
 * printing a second line, so the timestamp is attached to the line it belongs to.
 *
 * <p>Only the displayed text changes. The handler runs at {@link EventPriority#LOW} so
 * {@link com.logvex.addon.chatlog.mc.ChatRecorder} has already read the original content: a
 * timestamp in the log file would be stored twice, once as the record's own field.
 */
public class ChatTimestampsModule extends AddonModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<ChatTimestampFormat.Style> style = sgGeneral.add(new EnumSetting.Builder<ChatTimestampFormat.Style>()
        .name("style")
        .description("How much of the clock to show.")
        .defaultValue(ChatTimestampFormat.Style.CLOCK)
        .build()
    );

    private final Setting<Boolean> dim = sgGeneral.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
        .name("dim")
        .description("Draw the prefix in dark grey so the message stays the brightest part of the line.")
        .defaultValue(true)
        .build()
    );

    public ChatTimestampsModule() {
        super(LogClient.CATEGORY, "chat-timestamps",
            "Prefixes incoming chat with the time it arrived.");
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive() || event.isCancelled() || event.getMessage() == null) return;

        String content = event.getMessage().getString();
        if (content == null || content.isBlank()) return;

        String prefix = ChatTimestampFormat.prefix(System.currentTimeMillis(), style.get());
        if (prefix.isEmpty()) return;

        MutableText stamp = Text.literal(prefix);
        if (dim.get()) stamp.formatted(Formatting.DARK_GRAY);

        event.setMessage(Text.empty().append(stamp).append(event.getMessage()));
    }
}
