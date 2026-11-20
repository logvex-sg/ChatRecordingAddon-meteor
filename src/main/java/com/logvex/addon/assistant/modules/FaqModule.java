package com.logvex.addon.assistant.modules;

import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.FaqCommand;
import com.logvex.addon.assistant.core.ReplyFormat;
import com.logvex.addon.assistant.mc.AssistantService;
import com.logvex.addon.automation.modules.AutomationModule;
import com.logvex.addon.framework.SafeEventHandler;
import com.logvex.addon.notify.core.NotificationType;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Optional;

/**
 * Owns {@code !faq}: add, list, remove, view and count entries in the assistant's memory.
 *
 * <p>A module of its own so the FAQ answers even with the assistant switched off, and so the write
 * subcommands carry a switch of their own. The rules live in {@link FaqCommand}; this class only
 * reads settings, talks to the store, and delivers the answer.
 *
 * <p>The command is intercepted on {@link SendMessageEvent} and that event is cancelled, so the line
 * never reaches the server. Only a line this module answers is cancelled.
 */
public class FaqModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chatOutput = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-output")
        .description("Answer in public chat. Off (the default) shows the answer only to you, so a "
            + "long listing cannot fill the server's chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxListed = sgGeneral.add(new IntSetting.Builder()
        .name("max-listed")
        .description("How many entries one listing shows before it asks you to narrow the search.")
        .defaultValue(FaqCommand.DEFAULT_LIMIT)
        .min(1)
        .max(200)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> allowRemoteWrite = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-remote-write")
        .description("Let other players add or remove entries through chat. Off by default so a "
            + "stranger cannot write into your memory.")
        .defaultValue(false)
        .build()
    );

    private final AssistantService service = AssistantService.get();
    private boolean sending;

    public FaqModule() {
        super("faq",
            "Answers !faq add/list/remove/view/count from the assistant's stored facts.",
            "facts", "qanda");
    }

    @Override
    public void onActivate() {
        service.loadAsync(null);
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        // Runs inside Meteor's dispatch: an unguarded throw would break ordinary chatting.
        SafeEventHandler.run("FaqModule.onSendMessage", () -> sendStep(event));
    }

    private void sendStep(SendMessageEvent event) {
        if (!isActive() || event == null || sending) return;
        String message = event.message;
        if (message == null) return;
        String head = commandPrefix() + FaqCommand.NAME;
        String trimmed = message.strip();
        if (!trimmed.startsWith(head)) return;
        // "!faqbook" is a different word, not this command.
        if (trimmed.length() > head.length()
            && !Character.isWhitespace(trimmed.charAt(head.length()))) {
            return;
        }
        handle(trimmed.substring(head.length()).strip());
        event.cancel();
    }

    private void handle(String rest) {
        AssistantMemory memory = service.memory();
        FaqCommand.Request request = FaqCommand.parse(rest);
        switch (request.kind()) {
            case USAGE -> show(FaqCommand.usage(commandPrefix()));
            case LIST -> list(memory, request.key());
            case ADD -> add(memory, request);
            case REMOVE -> remove(memory, request.key());
            case VIEW -> view(memory, request.key());
            case COUNT -> show(FaqCommand.count(memory.facts().size(), commandPrefix()));
        }
    }

    private void list(AssistantMemory memory, String filter) {
        List<FaqCommand.Entry> matched = FaqCommand.matching(memory, filter);
        if (matched.isEmpty()) {
            show(filter.isBlank()
                ? "No FAQ entries yet. Add one with " + commandPrefix() + FaqCommand.NAME
                    + " add <key> = <answer>."
                : "No FAQ entry matches \"" + filter + "\".");
            return;
        }
        show(FaqCommand.listing(matched, maxListed.get(), commandPrefix(),
            memory.facts().size()));
    }

    private void add(AssistantMemory memory, FaqCommand.Request request) {
        boolean replaced = FaqCommand.replaces(memory, request.key());
        String problem = FaqCommand.addProblem(request, memory.facts().size(), replaced);
        if (!problem.isEmpty()) {
            show(problem);
            return;
        }
        if (!mayWrite()) {
            show("Only " + localName() + " can add FAQ entries.");
            return;
        }
        if (!memory.rememberFact(request.key(), request.value(), System.currentTimeMillis())) {
            show("That key is not usable.");
            return;
        }
        service.saveAsync();
        String number = FaqCommand.resolve(memory, request.key())
            .map(entry -> " (#" + entry.number() + ")")
            .orElse("");
        show((replaced ? "Updated" : "Added") + " FAQ entry" + number + ": " + request.key() + ".");
    }

    /**
     * Deletes the entry named by a number or a key.
     *
     * <p>A number is resolved through the same numbering the listing prints, so
     * {@code remove 30} deletes the row that was shown as 30.
     */
    private void remove(AssistantMemory memory, String token) {
        if (token.isBlank()) {
            show("Usage: " + commandPrefix() + FaqCommand.NAME + " remove <number|key>");
            return;
        }
        if (!mayWrite()) {
            show("Only " + localName() + " can remove FAQ entries.");
            return;
        }
        Optional<FaqCommand.Entry> found = FaqCommand.resolve(memory, token);
        if (found.isEmpty()) {
            show(noEntry(token));
            return;
        }
        FaqCommand.Entry entry = found.get();
        if (!memory.forgetFact(entry.key())) {
            show(noEntry(token));
            return;
        }
        service.saveAsync();
        show("Removed FAQ #" + entry.number() + ": " + entry.key() + ".");
    }

    private void view(AssistantMemory memory, String token) {
        if (token.isBlank()) {
            show("Usage: " + commandPrefix() + FaqCommand.NAME + " <number|key>");
            return;
        }
        Optional<FaqCommand.Entry> found = FaqCommand.resolve(memory, token);
        if (found.isEmpty()) {
            show(noEntry(token));
            return;
        }
        show(FaqCommand.detail(found.get()));
    }

    /** The answer for a number or key that is not stored, naming what the user typed. */
    private String noEntry(String token) {
        int number = FaqCommand.parseNumber(token);
        if (number > 0) {
            return "There is no FAQ entry #" + number + " (the list is 1-"
                + FaqCommand.MAX_ENTRIES + "). Try " + commandPrefix() + FaqCommand.NAME + " list.";
        }
        return "There is no FAQ entry called \"" + token + "\". Try " + commandPrefix()
            + FaqCommand.NAME + " list.";
    }

    /**
     * True when this player may change the store.
     *
     * <p>Writes are local by default because the FAQ lives in the client's own memory file: a
     * stranger in public chat should not be able to edit what this client remembers.
     */
    private boolean mayWrite() {
        return allowRemoteWrite.get() || isLocalPlayer();
    }

    private boolean isLocalPlayer() {
        return !localName().isBlank();
    }

    private String localName() {
        if (mc.player == null) return "";
        var profile = mc.player.getGameProfile();
        return profile == null || profile.getName() == null ? "" : profile.getName();
    }

    /** The assistant's configured prefix, so both features spell the command the same way. */
    private String commandPrefix() {
        AssistantModule assistant = meteordevelopment.meteorclient.systems.modules.Modules.get()
            .get(AssistantModule.class);
        String prefix = assistant == null ? "!" : assistant.config().commandPrefix();
        return prefix == null || prefix.isBlank() ? "!" : prefix;
    }

    /** Delivers one line, in public chat or to this client only. */
    private void show(String text) {
        if (text == null || text.isBlank()) return;
        if (!chatOutput.get()) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "FAQ", text);
            return;
        }
        send(text.strip());
    }

    /** Delivers a block, in public chat one line at a time or as a single notification. */
    private void show(ReplyFormat.Embed embed) {
        if (embed == null) return;
        List<String> lines = ReplyFormat.render(embed);
        if (lines.isEmpty()) return;
        if (!chatOutput.get()) {
            notifyUser(NotificationType.IMPORTANT_CHAT, "FAQ", String.join("\n", lines));
            return;
        }
        for (String line : lines) send(line);
    }

    private void send(String line) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        sending = true;
        try {
            ChatUtils.sendPlayerMsg(line);
        } finally {
            sending = false;
        }
    }
}
