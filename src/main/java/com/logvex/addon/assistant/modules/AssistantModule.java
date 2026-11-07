package com.logvex.addon.assistant.modules;

import com.logvex.addon.assistant.core.AssistantBrain;
import com.logvex.addon.assistant.core.AssistantConfig;
import com.logvex.addon.assistant.core.AssistantMemory;
import com.logvex.addon.assistant.core.AssistantRuntime;
import com.logvex.addon.assistant.core.TriggerRule;
import com.logvex.addon.assistant.core.TriggerSet;
import com.logvex.addon.assistant.gui.AssistantScreen;
import com.logvex.addon.assistant.mc.AssistantService;
import com.logvex.addon.automation.modules.AutomationModule;
import com.logvex.addon.chatlog.core.ChatClassifier;
import com.logvex.addon.chatlog.core.MessageType;
import com.logvex.addon.common.Messages;
import com.logvex.addon.framework.events.game.ReceiveMessageEvent;
import com.logvex.addon.framework.events.world.TickEvent;
import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.widgets.WWidget;
import com.logvex.addon.framework.gui.widgets.containers.WVerticalList;
import com.logvex.addon.framework.gui.widgets.pressable.WButton;
import com.logvex.addon.framework.orbit.EventHandler;
import com.logvex.addon.framework.settings.BoolSetting;
import com.logvex.addon.framework.settings.EnumSetting;
import com.logvex.addon.framework.settings.IntSetting;
import com.logvex.addon.framework.settings.Setting;
import com.logvex.addon.framework.settings.SettingGroup;
import com.logvex.addon.framework.settings.StringListSetting;
import com.logvex.addon.framework.settings.StringSetting;
import com.logvex.addon.framework.utils.player.ChatUtils;
import com.logvex.addon.notify.core.NotificationType;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A configurable chat assistant: keyword triggers, persistent memory and memory commands.
 *
 * <p>When enabled, the module watches incoming chat and asks {@link AssistantRuntime} whether any
 * configured trigger matches or whether the message is a memory command. It never speaks unless a
 * rule explicitly asks it to, so an empty configuration is silent. The decision logic itself lives
 * in the Minecraft-free core; this class only turns a decision into a chat line, a notification, a
 * sound or a memory write.
 *
 * <p>Self-replies are suppressed: a message whose sender is the local player, or whose text is one
 * of the assistant's own recent replies, is ignored, so two assistants cannot talk to each other and
 * the assistant cannot answer itself.
 */
public class AssistantModule extends AutomationModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("Triggers");
    private final SettingGroup sgMemory = settings.createGroup("Memory");
    private final SettingGroup sgLimits = settings.createGroup("Limits");

    private final Setting<String> assistantName = sgGeneral.add(new StringSetting.Builder()
        .name("name")
        .description("The assistant's name, shown in the HUD and in notifications.")
        .defaultValue("Assistant")
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> respondToTriggers = sgGeneral.add(new BoolSetting.Builder()
        .name("respond-to-triggers")
        .description("Answer messages that match a configured keyword trigger.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> memoryCommands = sgGeneral.add(new BoolSetting.Builder()
        .name("memory-commands")
        .description("Understand !remember, !forget, !fact, !facts, !memory and !history in chat.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<String> commandPrefix = sgGeneral.add(new StringSetting.Builder()
        .name("command-prefix")
        .description("The prefix memory commands start with.")
        .defaultValue("!")
        .visible(memoryCommands::get)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> watchPublicChat = sgTriggers.add(new BoolSetting.Builder()
        .name("watch-public-chat")
        .description("Watch public player chat for triggers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> watchPrivateMessages = sgTriggers.add(new BoolSetting.Builder()
        .name("watch-private-messages")
        .description("Watch private messages for triggers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> watchSystem = sgTriggers.add(new BoolSetting.Builder()
        .name("watch-system")
        .description("Watch system and server messages for triggers. Off by default: they are noisy.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> rules = sgTriggers.add(new StringListSetting.Builder()
        .name("rules")
        .description("Trigger rules, one per line: pattern;sender=;reply=;mode=;cooldown=;limit=;sound=;remember=;enabled=")
        .defaultValue(TriggerRule.defaults().stream().map(TriggerRule::encode).toList())
        .wide()
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> rememberConversations = sgMemory.add(new BoolSetting.Builder()
        .name("remember-conversations")
        .description("Remember recent exchanges in memory, so !history and {fact:...} stay useful.")
        .defaultValue(true)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<TriggerRule.ReplyMode> memoryReplyMode =
        sgMemory.add(new EnumSetting.Builder<TriggerRule.ReplyMode>()
            .name("memory-reply-mode")
            .description("How the assistant answers memory commands: chat, notify, or silent.")
            .defaultValue(TriggerRule.ReplyMode.NOTIFY)
            .onChanged(ignored -> rebuild())
            .build()
        );

    private final Setting<Boolean> persist = sgMemory.add(new BoolSetting.Builder()
        .name("persist")
        .description("Save memory to disk so it survives a restart.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rateLimit = sgLimits.add(new IntSetting.Builder()
        .name("rate-limit-per-minute")
        .description("Maximum assistant replies per minute. 0 disables the global limit.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 120)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Integer> maxReplyLength = sgLimits.add(new IntSetting.Builder()
        .name("max-reply-length")
        .description("Longest reply the assistant will send, in characters.")
        .defaultValue(200)
        .min(16)
        .sliderRange(16, 400)
        .onChanged(ignored -> rebuild())
        .build()
    );

    private final Setting<Boolean> matchFeedback = sgLimits.add(new BoolSetting.Builder()
        .name("match-feedback")
        .description("Print a short note when a trigger matches or the assistant starts.")
        .defaultValue(false)
        .build()
    );

    private final AssistantService service = AssistantService.get();
    private final AssistantRuntime runtime;

    private TriggerSet parsed = TriggerSet.empty();
    private AssistantConfig config = AssistantConfig.DEFAULTS;
    private String ownName = "";

    /** The assistant's own recent replies, so it never answers itself. */
    private final Deque<String> ownReplies = new ArrayDeque<>();

    private long lastSaveAt;
    private boolean memoryDirty;

    public AssistantModule() {
        super("assistant", "A configurable chat assistant with keyword triggers and persistent memory.");
        runtime = new AssistantRuntime(service.memory());
    }

    @Override
    public void onActivate() {
        service.loadAsync(this::rebuild);
        rebuild();
        ownName = localPlayerName();
        lastSaveAt = System.currentTimeMillis();
        info("%s enabled (%s).", config.name(), Messages.plural(parsed.rules().size(), "trigger"));
    }

    @Override
    public void onDeactivate() {
        flushMemory();
        info("%s disabled.", config.name());
    }

    @Override
    protected void onWorldJoin() {
        ownName = localPlayerName();
        runtime.resetSession();
    }

    @Override
    protected void onWorldLeave() {
        runtime.resetSession();
        ownReplies.clear();
        flushMemory();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Persist on a slow cadence rather than on every memory write, so a busy chat cannot cause
        // a disk write storm. The dirty flag makes this a no-op when nothing changed.
        if (!memoryDirty || !persist.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastSaveAt < 30_000L) return;
        flushMemory();
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!ready() || event.getMessage() == null) return;

        boolean fromPlayer = event.getIndicator() != null;
        String content = event.getMessage().getString();
        if (content == null || content.isBlank()) return;

        ChatClassifier.Result result = ChatClassifier.classify(fromPlayer, content);
        if (!watched(result.type())) return;

        String sender = result.sender().isBlank() ? "" : result.sender();
        if (isOwnMessage(sender, content)) return;

        AssistantBrain.Decision decision = runtime.handle(sender, content, System.currentTimeMillis());
        if (!decision.acts()) return;
        deliver(decision, sender);
    }

    // ------------------------------------------------------------------ delivery

    private void deliver(AssistantBrain.Decision decision, String sender) {
        switch (decision.delivery()) {
            case CHAT -> speak(decision.text());
            case NOTIFY -> notifyUser(NotificationType.IMPORTANT_CHAT, config.name(), decision.text());
            case SILENT -> {
                // Nothing visible; memory bookkeeping already happened in the brain.
            }
            case NONE -> {
                return;
            }
        }
        if (decision.sound()) playAlert();
        if (decision.remember()) memoryDirty = true;
        if (matchFeedback.get() && decision.rule() != null) {
            info("Trigger '%s' matched for %s.", decision.rule().pattern(), Messages.player(sender));
        }
    }

    /** Sends {@code text} as a real chat message, guarding against self-reply loops. */
    private void speak(String text) {
        if (text == null || text.isBlank()) return;
        if (mc.player == null || mc.player.networkHandler == null) return;
        String trimmed = text.strip();
        ownReplies.addLast(trimmed.toLowerCase(java.util.Locale.ROOT));
        while (ownReplies.size() > 16) ownReplies.removeFirst();
        ChatUtils.sendPlayerMsg(trimmed);
    }

    private void playAlert() {
        if (mc.player == null) return;
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 1.5f);
    }

    // ------------------------------------------------------------------ helpers

    private boolean watched(MessageType type) {
        return switch (type) {
            case PUBLIC_CHAT -> watchPublicChat.get();
            case PLAYER_MESSAGE -> watchPrivateMessages.get();
            case SYSTEM, ANNOUNCEMENT, GAME, JOIN, LEAVE, DEATH -> watchSystem.get();
            case OTHER -> false;
        };
    }

    private boolean isOwnMessage(String sender, String content) {
        if (!ownName.isBlank() && sender != null && sender.equalsIgnoreCase(ownName)) return true;
        return ownReplies.contains(content.strip().toLowerCase(java.util.Locale.ROOT));
    }

    private String localPlayerName() {
        if (mc.player == null) return "";
        var profile = mc.player.getGameProfile();
        return profile == null || profile.getName() == null ? "" : profile.getName();
    }

    /** Rebuilds the runtime's rules and config from the current settings. */
    public void rebuild() {
        parsed = TriggerSet.of(parseRules());
        config = new AssistantConfig(
            assistantName.get(),
            respondToTriggers.get(),
            memoryCommands.get(),
            commandPrefix.get(),
            memoryReplyMode.get(),
            rememberConversations.get(),
            rateLimit.get(),
            maxReplyLength.get()
        );
        runtime.setTriggers(parsed);
        runtime.setConfig(config);
    }

    private List<TriggerRule> parseRules() {
        List<TriggerRule> out = new ArrayList<>();
        for (String encoded : rules.get()) {
            TriggerRule rule = TriggerRule.decode(encoded);
            if (rule != null) out.add(rule);
        }
        return out;
    }

    private void flushMemory() {
        if (!persist.get()) {
            memoryDirty = false;
            return;
        }
        service.saveAsync();
        memoryDirty = false;
        lastSaveAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ public API

    public AssistantRuntime runtime() {
        return runtime;
    }

    public AssistantMemory memory() {
        return service.memory();
    }

    public TriggerSet triggers() {
        return parsed;
    }

    public AssistantConfig config() {
        return config;
    }

    /** The encoded rule list, for the GUI to edit in place. */
    @SuppressWarnings("unchecked")
    public Setting<List<String>> rulesSetting() {
        return (Setting<List<String>>) settings.get("rules");
    }

    /** Adds a rule and rebuilds. Returns false when the pattern was blank. */
    public boolean addRule(TriggerRule rule) {
        if (rule == null || rule.pattern().isBlank()) return false;
        List<String> updated = new ArrayList<>(rules.get());
        updated.add(rule.encode());
        rules.set(updated);
        rebuild();
        return true;
    }

    public boolean removeRule(int index) {
        List<String> updated = new ArrayList<>(rules.get());
        if (index < 0 || index >= updated.size()) return false;
        updated.remove(index);
        rules.set(updated);
        rebuild();
        return true;
    }

    public boolean replaceRule(int index, String encoded) {
        List<String> updated = new ArrayList<>(rules.get());
        if (index < 0 || index >= updated.size()) return false;
        TriggerRule rule = TriggerRule.decode(encoded);
        updated.set(index, rule == null ? encoded : rule.encode());
        rules.set(updated);
        rebuild();
        return true;
    }

    public void restoreDefaultRules() {
        rules.set(TriggerRule.defaults().stream().map(TriggerRule::encode).toList());
        rebuild();
    }

    /** Runs {@code text} as if it arrived from {@code sender}; used by the test command. */
    public AssistantBrain.Decision simulate(String sender, String content) {
        AssistantBrain.Decision decision = runtime.handle(sender, content, System.currentTimeMillis());
        if (decision.acts()) deliver(decision, sender);
        return decision;
    }

    /** Persists memory immediately (GUI and command use this). */
    public void saveMemory() {
        flushMemory();
    }

    public void clearMemory() {
        service.clear();
        runtime.resetSession();
        info("Memory cleared.");
    }

    public String statusLine() {
        return runtime.statusLine();
    }

    // ------------------------------------------------------------------ widget

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WButton open = list.add(theme.button("Assistant Manager...")).expandX().widget();
        open.action = () -> mc.setScreen(new AssistantScreen(this));

        WButton save = list.add(theme.button("Save Memory")).expandX().widget();
        save.action = this::saveMemory;

        WButton defaults = list.add(theme.button("Restore Default Triggers")).expandX().widget();
        defaults.action = () -> {
            restoreDefaultRules();
            info("Restored the default triggers.");
        };
        return list;
    }
}
