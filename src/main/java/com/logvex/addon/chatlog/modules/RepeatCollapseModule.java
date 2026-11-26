package com.logvex.addon.chatlog.modules;

import com.logvex.addon.LogClient;
import com.logvex.addon.chatlog.core.RepeatGuard;
import com.logvex.addon.framework.systems.modules.AddonModule;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import com.logvex.addon.framework.SafeEventHandler;

/**
 * Collapses a run of identical chat lines into the first one plus a repeat count.
 *
 * <p>Spam, a stuck macro and a server announcing the same thing twice all look alike: the same text
 * arriving again. The first line is shown normally; each repeat inside the window is cancelled and
 * counted, and the count is reported once the run ends. A player who says the same thing again after
 * other messages is not repeating themselves, so any different line starts a new run.
 *
 * <p>Repeat counts are reported only in chat, never in the log: the recorder still stores every
 * message, because the log is evidence and dropping entries would make it lie about what arrived.
 */
public class RepeatCollapseModule extends AddonModule {
    /** Between the recorder ({@code MEDIUM}) and the timestamp prefix ({@code LOW}). */
    private static final int REPEAT_PRIORITY = (EventPriority.MEDIUM + EventPriority.LOW) / 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> windowMs = sgGeneral.add(new IntSetting.Builder()
        .name("window")
        .description("How soon the same line must arrive to count as a repeat, in milliseconds.")
        .defaultValue(3000)
        .min(0)
        .sliderRange(0, 30_000)
        .build()
    );

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce-count")
        .description("Print how many times a line was repeated once the run ends.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreOwn = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-own")
        .description("Do not collapse your own messages, so a macro you are testing stays visible.")
        .defaultValue(false)
        .build()
    );

    private final RepeatGuard guard = new RepeatGuard();
    private int pendingCount;
    private long lastRepeatAt;

    public RepeatCollapseModule() {
        super(LogClient.CATEGORY, "repeat-collapse",
            "Hides repeated identical chat lines and reports how many times one repeated.");
    }

    @Override
    public void onActivate() {
        guard.reset();
        pendingCount = 0;
        lastRepeatAt = 0;
    }

    @Override
    public void onDeactivate() {
        flushPending();
        guard.reset();
    }

    /**
     * Runs after the recorder but before {@code chat-timestamps}.
     *
     * <p>The priority is deliberately between {@link EventPriority#MEDIUM} (the recorder) and
     * {@link EventPriority#LOW} (the timestamp prefix): the recorder must already have stored the
     * line, but the prefix must not yet have been added, or two spam lines a second apart would
     * compare unequal and never collapse.
     */
    @EventHandler(priority = REPEAT_PRIORITY)
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!isActive() || event.isCancelled() || event.getMessage() == null) return;

        String content = event.getMessage().getString();
        if (content == null || content.isBlank()) return;
        if (ignoreOwn.get() && isOwnMessage(event)) return;

        // Sender and content together: two different players saying the same thing is not a repeat.
        String key = senderOf(event) + "\u0000" + content;

        if (guard.accept(key, System.currentTimeMillis(), windowMs.get())) {
            flushPending();
            return;
        }

        event.cancel();
        pendingCount = guard.run() - 1;
        lastRepeatAt = System.currentTimeMillis();
    }

    /**
     * Reports a run once it has gone quiet.
     *
     * <p>Waiting for the next different line would leave the count unsaid for as long as the spam
     * lasts, which is exactly when the user wants to know how much was hidden.
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        SafeEventHandler.guard("RepeatCollapseModule.onTick", () -> {
            if (pendingCount <= 0) return;
            if (System.currentTimeMillis() - lastRepeatAt >= Math.max(1, windowMs.get())) flushPending();
        }).run();
    }

    /** Reports the run that just ended, if it was longer than the one line already shown. */
    private void flushPending() {
        int count = pendingCount;
        pendingCount = 0;
        if (count <= 0 || !announce.get()) return;
        info("(dim)(%d more of the same line)(default)", count);
    }

    private static String senderOf(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        // The sender is not on the event, so the classifier recovers it from the line itself.
        return com.logvex.addon.chatlog.core.ChatClassifier
            .classify(event.getIndicator() != null, text).sender();
    }

    private boolean isOwnMessage(ReceiveMessageEvent event) {
        var mc = com.logvex.addon.framework.LogClientContext.mc;
        if (mc == null || mc.player == null) return false;
        String own = mc.player.getGameProfile().getName();
        return own != null && own.equalsIgnoreCase(senderOf(event));
    }
}
